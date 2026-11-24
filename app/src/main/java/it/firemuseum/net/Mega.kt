package it.firemuseum.net

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Cartella pubblica MEGA in sola lettura (link `https://mega.nz/folder/<id>#<chiave>`), senza account.
 *
 * - Elenco: API pubblica `a:"f"` con `n=<id cartella>`; nomi e chiavi dei file sono cifrati con la
 *   chiave del link (AES-ECB per le chiavi, AES-CBC IV zero per gli attributi).
 * - Lettura: `a:"g"` restituisce un URL di download; MEGA accetta intervalli in coda all'URL
 *   (`/<inizio>-<fine>`) e il contenuto è AES-CTR, quindi si decifra da qualsiasi posizione:
 *   un tassello di una piramide zip = una richiesta piccola, senza scaricare l'intero file.
 */
class MegaFolder private constructor(private val net: Net, val folderId: String, private val shareKey: ByteArray) {

    class MegaFile(val name: String, val handle: String, val size: Long, val key: ByteArray, val nonce: ByteArray)

    @Volatile var files: Map<String, MegaFile> = emptyMap(); private set
    private val urls = ConcurrentHashMap<String, Pair<String, Long>>()
    private val listLock = Mutex()

    private suspend fun api(cmd: JSONObject): Any {
        val url = "https://g.api.mega.co.nz/cs?id=${Random.nextInt(1, Int.MAX_VALUE)}&n=$folderId"
        val text = net.postJson(url, JSONArray().put(cmd).toString())
        val v = if (text.trim().startsWith("[")) JSONArray(text).get(0) else text.trim().toInt()
        if (v is Int && v < 0) throw IOException("MEGA errore $v")
        return v
    }

    /** Rilegge l'elenco della cartella; restituisce la risposta grezza (da salvare per l'avvio offline). */
    suspend fun refresh(): JSONObject = listLock.withLock {
        val res = api(JSONObject().put("a", "f").put("c", 1).put("ca", 1).put("r", 1)) as JSONObject
        files = parse(res)
        res
    }

    fun parse(res: JSONObject): Map<String, MegaFile> {
        val arr = res.optJSONArray("f") ?: return emptyMap()
        val handles = HashSet<String>()
        for (i in 0 until arr.length()) handles += arr.getJSONObject(i).optString("h")
        val roots = HashSet<String>()
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { if (it.optString("p") !in handles) roots += it.optString("h") }
        val out = HashMap<String, MegaFile>()
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i)
            if (n.optInt("t", -1) != 0) continue
            runCatching {
                val piece = n.optString("k").split('/').map { it.split(':', limit = 2) }
                    .firstOrNull { it.size == 2 && it[0] in roots }?.get(1) ?: return@runCatching
                val k = ecbDecrypt(shareKey, b64(piece))
                if (k.size < 32) return@runCatching
                val key = ByteArray(16) { (k[it].toInt() xor k[it + 16].toInt()).toByte() }
                val attr = cbcDecrypt(key, b64(n.getString("a")))
                val s = String(attr, Charsets.UTF_8).trimEnd('\u0000')
                if (!s.startsWith("MEGA")) return@runCatching
                val name = JSONObject(s.substring(4)).getString("n")
                out[name] = MegaFile(name, n.getString("h"), n.getLong("s"), key, k.copyOfRange(16, 24))
            }.onFailure { Log.w(TAG, "nodo illeggibile: ${it.message}") }
        }
        return out
    }

    /** Ripristina un elenco salvato (avvio senza rete o più rapido). */
    fun restore(raw: JSONObject) { if (files.isEmpty()) files = parse(raw) }

    private suspend fun downloadUrl(f: MegaFile, fresh: Boolean): String {
        val now = System.currentTimeMillis()
        if (!fresh) urls[f.handle]?.let { (u, t) -> if (now - t < URL_TTL) return u }
        val r = api(JSONObject().put("a", "g").put("g", 1).put("n", f.handle)) as JSONObject
        val u = r.getString("g")
        urls[f.handle] = u to now
        return u
    }

    /** Byte [start, end) del file, già decifrati. */
    suspend fun read(f: MegaFile, start: Long, end: Long): ByteArray {
        val e = minOf(end, f.size)
        if (start >= e) return ByteArray(0)
        val aligned = start - start % 16
        var fresh = false
        while (true) {
            val url = downloadUrl(f, fresh) + "/$aligned-${e - 1}"
            val data = try {
                net.bytes(url, retries = 2) ?: throw IOException("MEGA: file assente")
            } catch (ex: HttpException) {
                if (!fresh && (ex.code == 403 || ex.code == 404 || ex.code == 410)) { fresh = true; continue }
                throw ex
            }
            return withContext(Dispatchers.Default) {
                val iv = f.nonce.copyOf(16)
                var ctr = aligned / 16
                for (i in 15 downTo 8) { iv[i] = (ctr and 0xff).toByte(); ctr = ctr ushr 8 }
                val c = Cipher.getInstance("AES/CTR/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(f.key, "AES"), IvParameterSpec(iv))
                val plain = c.doFinal(data)
                plain.copyOfRange((start - aligned).toInt(), plain.size)
            }
        }
    }

    suspend fun readAll(f: MegaFile): ByteArray = read(f, 0, f.size)

    companion object {
        private const val TAG = "Mega"
        private const val URL_TTL = 45 * 60_000L

        private val LINK = Regex("mega\\.nz/folder/([^#/?]+)#([^/?\\s]+)")

        fun open(net: Net, link: String): MegaFolder? {
            val m = LINK.find(link) ?: return null
            val key = runCatching { b64(m.groupValues[2]) }.getOrNull()?.takeIf { it.size == 16 } ?: return null
            return MegaFolder(net, m.groupValues[1], key)
        }

        fun b64(s: String): ByteArray = Base64.decode(s.replace('-', '+').replace('_', '/'), Base64.NO_WRAP or Base64.NO_PADDING)

        private fun ecbDecrypt(key: ByteArray, data: ByteArray): ByteArray =
            Cipher.getInstance("AES/ECB/NoPadding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(data) }

        private fun cbcDecrypt(key: ByteArray, data: ByteArray): ByteArray =
            Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16))); doFinal(data)
            }
    }
}

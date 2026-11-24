package it.firemuseum.net

import android.util.Log
import it.firemuseum.ui.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int, msg: String) : IOException(msg)

/**
 * Rete "educata": massimo 4 richieste per host (Wikimedia, musei), back-off automatico
 * su 429/503 rispettando Retry-After, cancellazione immediata delle richieste di tasselli
 * che non servono più (l'utente ha già spostato la vista).
 */
class Net(private val settings: Settings) {

    private val pausedUntil = ConcurrentHashMap<String, Long>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests = 12; maxRequestsPerHost = 4 })
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", settings.userAgent).build())
        }
        .build()

    private suspend fun respectBackoff(host: String) {
        val until = pausedUntil[host] ?: return
        val wait = until - System.currentTimeMillis()
        if (wait > 0) delay(wait)
    }

    private fun noteBackoff(host: String, r: Response) {
        val ra = r.header("Retry-After")?.toLongOrNull()?.times(1000) ?: 5000L
        pausedUntil[host] = System.currentTimeMillis() + ra.coerceIn(1000, 120_000)
        Log.w(TAG, "HTTP ${r.code} from $host: pausing ${ra}ms")
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }

    /** GET in memoria (tasselli, JSON). null su 404/410. Ripete su errori temporanei. */
    suspend fun bytes(url: String, retries: Int = 3): ByteArray? {
        val req = Request.Builder().url(url).build()
        val host = req.url.host
        var attempt = 0
        while (true) {
            respectBackoff(host)
            try {
                client.newCall(req).await().use { r ->
                    when {
                        r.isSuccessful -> return withContext(Dispatchers.IO) { r.body!!.bytes() }
                        r.code == 404 || r.code == 410 -> return null
                        r.code == 429 || r.code == 503 -> noteBackoff(host, r)
                        r.code >= 500 -> Unit
                        else -> throw HttpException(r.code, "HTTP ${r.code} $url")
                    }
                }
            } catch (e: HttpException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= retries) throw e
            }
            attempt++
            if (attempt > retries) throw IOException("giving up $url")
            delay(400L * attempt * attempt)
        }
    }

    suspend fun json(url: String): JSONObject? = bytes(url)?.let { JSONObject(String(it, Charsets.UTF_8)) }

    /** Query SPARQL su Wikidata (POST per query lunghe). */
    suspend fun sparql(query: String): JSONObject? {
        val host = "query.wikidata.org"
        respectBackoff(host)
        val req = Request.Builder()
            .url("https://query.wikidata.org/sparql")
            .header("Accept", "application/sparql-results+json")
            .post(FormBody.Builder().add("query", query).build())
            .build()
        client.newCall(req).await().use { r ->
            if (r.code == 429) { noteBackoff(host, r); return null }
            if (!r.isSuccessful) return null
            return withContext(Dispatchers.IO) { JSONObject(r.body!!.string()) }
        }
    }

    /**
     * Download in streaming con ripresa. Bloccante: chiamare da Dispatchers.IO.
     * [block] riceve (stream, contentLength, resumed) – resumed=false se il server ignora Range.
     */
    fun stream(url: String, from: Long, block: (InputStream, Long, Boolean) -> Unit) {
        val b = Request.Builder().url(url)
        if (from > 0) b.header("Range", "bytes=$from-")
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code} $url")
            val resumed = r.code == 206
            val body = r.body ?: throw IOException("empty body")
            block(body.byteStream(), body.contentLength(), resumed)
        }
    }

    companion object { private const val TAG = "Net" }
}

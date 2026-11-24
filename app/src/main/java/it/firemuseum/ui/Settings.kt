package it.firemuseum.ui

import android.content.Context

/** Preferenze persistenti (SharedPreferences). Valori di default pensati per Fire TV Stick 4K / 4K Max. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Link fisso dell'archivio MEGA scritto da tools/mega_pack.py in assets/mega_link.txt. */
    private val defaultMega: String = runCatching { ctx.assets.open("mega_link.txt").bufferedReader().readText().trim() }.getOrDefault("")

    /** Link pubblico della cartella MEGA con indice e piramidi (vuoto = non usarlo). */
    var megaLink: String
        get() = p.getString("mega", null) ?: defaultMega
        set(v) = p.edit().putString("mega", v).apply()

    /** Budget massimo della cache tasselli sul disco esterno (byte). 0 = automatico (60% dello spazio libero). */
    var tileCacheMaxBytes: Long
        get() = p.getLong("tileCacheMax", 0L)
        set(v) = p.edit().putLong("tileCacheMax", v).apply()

    /** Spazio che deve SEMPRE restare libero sul volume (byte). Default: max(1 GB, 5%). */
    var reserveBytes: Long
        get() = p.getLong("reserve", 0L)
        set(v) = p.edit().putLong("reserve", v).apply()

    /** Se non c'è disco esterno: consentire una piccola cache interna? (default sì ma minuscola) */
    var allowInternalFallback: Boolean
        get() = p.getBoolean("internalFallback", true)
        set(v) = p.edit().putBoolean("internalFallback", v).apply()

    var internalFallbackBytes: Long
        get() = p.getLong("internalFallbackBytes", 200L shl 20)
        set(v) = p.edit().putLong("internalFallbackBytes", v).apply()

    /** URL base di un server LAN (es. http://192.168.1.10:8000/) che serve piramidi DZI preparate sul PC. */
    var lanServer: String
        get() = p.getString("lan", "") ?: ""
        set(v) = p.edit().putString("lan", v).apply()

    /** User-Agent: Wikimedia chiede un UA identificabile con un contatto. Modificabile dalle impostazioni. */
    var userAgent: String
        get() = p.getString("ua", DEFAULT_UA) ?: DEFAULT_UA
        set(v) = p.edit().putString("ua", v).apply()

    var kidsOnly: Boolean
        get() = p.getBoolean("kids", false)
        set(v) = p.edit().putBoolean("kids", v).apply()

    var slideSeconds: Int
        get() = p.getInt("slideSec", 180)
        set(v) = p.edit().putInt("slideSec", v).apply()

    /** Ritmo dei voli dello slideshow rispetto alla navigazione manuale (0,1 molto lento … 0,5 vivace). */
    var slidePace: Float
        get() = p.getFloat("slidePace", 0.15f)
        set(v) = p.edit().putFloat("slidePace", v).apply()

    /** Velocità percepita di pan/zoom (0.5 lento … 2.0 veloce). */
    var speed: Float
        get() = p.getFloat("speed", 1f)
        set(v) = p.edit().putFloat("speed", v).apply()

    var favorites: Set<String>
        get() = p.getStringSet("fav", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("fav", v).apply()

    fun toggleFavorite(id: String): Boolean {
        val s = favorites.toMutableSet()
        val now = if (s.contains(id)) { s.remove(id); false } else { s.add(id); true }
        favorites = s
        return now
    }

    companion object {
        const val DEFAULT_UA = "FireMuseum/0.1 (private non-commercial Fire TV art viewer; set contact in settings)"
    }
}

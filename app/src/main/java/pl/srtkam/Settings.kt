package pl.srtkam

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject

/**
 * Wszystkie ustawienia aplikacji w jednym miejscu.
 * Nazwa punktu i streamid powstaja z prefiksu i numeru punktu - dzieki temu
 * konfiguracje mozna rozeslac kodem QR, a na kazdym telefonie zmienic tylko numer.
 */
class Settings(private val context: Context) {

    private val p = PreferenceManager.getDefaultSharedPreferences(context)

    // --- Punkt kamerowy ---
    val prefix: String get() = (p.getString("point_prefix", "kam") ?: "kam").trim().ifBlank { "kam" }
    val pointNumber: Int get() = p.getString("point_number", "1")?.toIntOrNull()?.coerceIn(1, 99) ?: 1

    /** np. "kam01" - identyfikator strumienia na serwerze */
    val streamId: String get() = "%s%02d".format(prefix, pointNumber)

    /** np. "KAM01" - duzy napis na ekranie */
    val cameraName: String get() = streamId.uppercase()

    // --- Polaczenie SRT ---
    val host: String get() = (p.getString("srt_host", "") ?: "").trim()
    val port: Int get() = p.getString("srt_port", "8890")?.toIntOrNull() ?: 8890
    val passphrase: String get() = p.getString("srt_passphrase", "") ?: ""
    val pbkeylen: Int get() = p.getString("srt_pbkeylen", "128")?.toIntOrNull() ?: 128

    /** Bufor SRT w ms. Przy slabym LTE mozna zejsc az do 10 sekund. */
    val latency: Int get() = p.getString("srt_latency", "2000")?.toIntOrNull() ?: 2000

    /** Ile pakietow trzymac w kolejce wysylkowej - odpornosc na chwilowy zanik sieci */
    val cacheSize: Int get() = p.getString("srt_cache", "400")?.toIntOrNull() ?: 400

    // --- Wideo ---
    val width: Int get() = resolutionPart(0)
    val height: Int get() = resolutionPart(1)
    private fun resolutionPart(i: Int): Int {
        val r = p.getString("video_resolution", "1920x1080") ?: "1920x1080"
        return r.split("x").getOrNull(i)?.toIntOrNull() ?: if (i == 0) 1920 else 1080
    }

    val fps: Int get() = p.getString("video_fps", "30")?.toIntOrNull() ?: 30
    val bitrateKbps: Int get() = p.getString("video_bitrate", "5000")?.toIntOrNull() ?: 5000
    val codec: String get() = p.getString("video_codec", "H264") ?: "H264"
    val keyframeInterval: Int get() = p.getString("video_keyframe", "1")?.toIntOrNull() ?: 1
    val adaptiveBitrate: Boolean get() = p.getBoolean("video_abr", true)
    val minBitrateKbps: Int get() = p.getString("video_bitrate_min", "1500")?.toIntOrNull() ?: 1500

    /** UVC = grabber USB, BACK = kamera wbudowana */
    val videoSource: String get() = p.getString("video_source", "UVC") ?: "UVC"

    // --- Audio ---
    val audioEnabled: Boolean get() = p.getBoolean("audio_enabled", true)
    val audioBitrateKbps: Int get() = p.getString("audio_bitrate", "128")?.toIntOrNull() ?: 128
    val audioSampleRate: Int get() = p.getString("audio_samplerate", "48000")?.toIntOrNull() ?: 48000

    // --- Zachowanie ---
    val autoReconnect: Boolean get() = p.getBoolean("auto_reconnect", true)

    /** Nadawaj od razu po otwarciu aplikacji - dla punktow bez obslugi */
    val autoStart: Boolean get() = p.getBoolean("auto_start", false)

    /** Prubuj wznowic nadawanie po restarcie telefonu */
    val autoStartOnBoot: Boolean get() = p.getBoolean("auto_start_boot", false)

    /** Wylacz podglad zaraz po starcie nadawania - mniej ciepla i baterii */
    val previewOffOnStart: Boolean get() = p.getBoolean("preview_off_on_start", false)

    // --- Telemetria ---
    val telemetryEnabled: Boolean get() = p.getBoolean("telemetry_enabled", true)
    private val telemetryPort: Int get() = p.getString("telemetry_port", "8080")?.toIntOrNull() ?: 8080
    private val telemetryHostOverride: String get() = (p.getString("telemetry_host", "") ?: "").trim()

    /** Domyslnie ten sam serwer co SRT, tylko inny port. Mozna nadpisac. */
    fun telemetryUrl(): String {
        if (!telemetryEnabled) return ""
        val h = telemetryHostOverride.ifBlank { host }
        if (h.isBlank()) return ""
        return "http://$h:$telemetryPort/t"
    }

    /**
     * Adres SRT ze wszystkimi parametrami.
     * srt://host:port?streamid=X&latency=Y&passphrase=Z&pbkeylen=N
     */
    fun buildUrl(): String {
        val sb = StringBuilder("srt://$host:$port?streamid=$streamId&latency=$latency")
        if (passphrase.length in 10..79) sb.append("&passphrase=$passphrase&pbkeylen=$pbkeylen")
        return sb.toString()
    }

    fun isConfigured(): Boolean = host.isNotBlank()

    /**
     * Podpis ustawien wplywajacych na budowe strumienia. Gdy sie zmieni,
     * strumien trzeba zlozyc od nowa.
     */
    fun buildSignature(): String = listOf(
        videoSource, width, height, fps, bitrateKbps, codec,
        keyframeInterval, audioEnabled, audioBitrateKbps, audioSampleRate
    ).joinToString("|")

    // ------------------------------------------------------------------
    // Kod QR - przenoszenie konfiguracji miedzy telefonami
    // ------------------------------------------------------------------

    /**
     * Konfiguracja do kodu QR. Swiadomie BEZ numeru punktu - kazdy telefon
     * ma wlasny i nie chcemy go nadpisac przy skanowaniu.
     */
    fun toQrJson(): String = JSONObject().apply {
        put("v", 1)
        put("prefix", prefix)
        put("host", host)
        put("port", port)
        put("pass", passphrase)
        put("pbkey", pbkeylen)
        put("lat", latency)
        put("cache", cacheSize)
        put("src", videoSource)
        put("res", "${width}x${height}")
        put("fps", fps)
        put("br", bitrateKbps)
        put("brmin", minBitrateKbps)
        put("codec", codec)
        put("key", keyframeInterval)
        put("abr", adaptiveBitrate)
        put("aud", audioEnabled)
        put("audbr", audioBitrateKbps)
        put("audsr", audioSampleRate)
        put("rec", autoReconnect)
        put("auto", autoStart)
        put("boot", autoStartOnBoot)
        put("prevoff", previewOffOnStart)
        put("tel", telemetryEnabled)
        put("telport", telemetryPort)
        put("telhost", telemetryHostOverride)
    }.toString()

    /** Wczytuje konfiguracje z kodu QR. Zwraca komunikat bledu albo null. */
    fun applyQrJson(text: String): String? {
        return try {
            val j = JSONObject(text)
            if (!j.has("host")) return "To nie jest kod konfiguracji SRT Kamera"
            p.edit().apply {
                putString("point_prefix", j.optString("prefix", "kam"))
                putString("srt_host", j.optString("host", ""))
                putString("srt_port", j.optInt("port", 8890).toString())
                putString("srt_passphrase", j.optString("pass", ""))
                putString("srt_pbkeylen", j.optInt("pbkey", 128).toString())
                putString("srt_latency", j.optInt("lat", 2000).toString())
                putString("srt_cache", j.optInt("cache", 400).toString())
                putString("video_source", j.optString("src", "UVC"))
                putString("video_resolution", j.optString("res", "1920x1080"))
                putString("video_fps", j.optInt("fps", 30).toString())
                putString("video_bitrate", j.optInt("br", 5000).toString())
                putString("video_bitrate_min", j.optInt("brmin", 1500).toString())
                putString("video_codec", j.optString("codec", "H264"))
                putString("video_keyframe", j.optInt("key", 1).toString())
                putBoolean("video_abr", j.optBoolean("abr", true))
                putBoolean("audio_enabled", j.optBoolean("aud", true))
                putString("audio_bitrate", j.optInt("audbr", 128).toString())
                putString("audio_samplerate", j.optInt("audsr", 48000).toString())
                putBoolean("auto_reconnect", j.optBoolean("rec", true))
                putBoolean("auto_start", j.optBoolean("auto", false))
                putBoolean("auto_start_boot", j.optBoolean("boot", false))
                putBoolean("preview_off_on_start", j.optBoolean("prevoff", false))
                putBoolean("telemetry_enabled", j.optBoolean("tel", true))
                putString("telemetry_port", j.optInt("telport", 8080).toString())
                putString("telemetry_host", j.optString("telhost", ""))
            }.apply()
            null
        } catch (e: Exception) {
            "Nie udalo sie odczytac kodu: ${e.message}"
        }
    }
}

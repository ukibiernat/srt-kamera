package pl.srtkam

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Wszystkie ustawienia aplikacji w jednym miejscu.
 * Czytane z SharedPreferences, edytowane w SettingsActivity.
 */
class Settings(context: Context) {

    private val p = PreferenceManager.getDefaultSharedPreferences(context)

    // --- Punkt kamerowy ---
    val cameraName: String get() = p.getString("camera_name", "KAM01") ?: "KAM01"

    // --- Polaczenie SRT ---
    val host: String get() = p.getString("srt_host", "") ?: ""
    val port: Int get() = p.getString("srt_port", "8890")?.toIntOrNull() ?: 8890
    val streamId: String get() = p.getString("srt_streamid", "kam01") ?: "kam01"
    val passphrase: String get() = p.getString("srt_passphrase", "") ?: ""
    val pbkeylen: Int get() = p.getString("srt_pbkeylen", "128")?.toIntOrNull() ?: 128

    /** Bufor SRT w milisekundach. Dla LTE/5G start od 1000 ms. */
    val latency: Int get() = p.getString("srt_latency", "1000")?.toIntOrNull() ?: 1000

    // --- Wideo ---
    val width: Int get() = p.getString("video_resolution", "1920x1080")?.split("x")?.get(0)?.toIntOrNull() ?: 1920
    val height: Int get() = p.getString("video_resolution", "1920x1080")?.split("x")?.get(1)?.toIntOrNull() ?: 1080
    val fps: Int get() = p.getString("video_fps", "30")?.toIntOrNull() ?: 30

    /** Bitrate w kbps. */
    val bitrateKbps: Int get() = p.getString("video_bitrate", "5000")?.toIntOrNull() ?: 5000
    val codec: String get() = p.getString("video_codec", "H264") ?: "H264"
    val keyframeInterval: Int get() = p.getString("video_keyframe", "1")?.toIntOrNull() ?: 1

    /** Adaptacyjny bitrate - obniza jakosc zamiast gubic obraz przy slabym LTE. */
    val adaptiveBitrate: Boolean get() = p.getBoolean("video_abr", true)
    val minBitrateKbps: Int get() = p.getString("video_bitrate_min", "2000")?.toIntOrNull() ?: 2000

    // --- Zrodlo obrazu ---
    /** UVC = grabber USB, BACK/FRONT = kamera wbudowana */
    val videoSource: String get() = p.getString("video_source", "UVC") ?: "UVC"

    // --- Audio ---
    val audioEnabled: Boolean get() = p.getBoolean("audio_enabled", true)
    val audioBitrateKbps: Int get() = p.getString("audio_bitrate", "128")?.toIntOrNull() ?: 128
    val audioSampleRate: Int get() = p.getString("audio_samplerate", "48000")?.toIntOrNull() ?: 48000

    // --- Stabilnosc ---
    val autoReconnect: Boolean get() = p.getBoolean("auto_reconnect", true)
    val autoStart: Boolean get() = p.getBoolean("auto_start", false)

    /**
     * Buduje adres SRT ze wszystkimi parametrami.
     * Format: srt://host:port?streamid=X&latency=Y&passphrase=Z&pbkeylen=N
     */
    fun buildUrl(): String {
        val sb = StringBuilder("srt://$host:$port?streamid=$streamId&latency=$latency")
        if (passphrase.length in 10..79) {
            sb.append("&passphrase=$passphrase&pbkeylen=$pbkeylen")
        }
        return sb.toString()
    }

    fun isConfigured(): Boolean = host.isNotBlank() && streamId.isNotBlank()
}

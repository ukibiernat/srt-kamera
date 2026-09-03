package pl.srtkam

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.telephony.CellSignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wysyla co kilka sekund malutki pakiet danych o stanie punktu kamerowego.
 *
 * Zasady:
 * - pakiet wazy ~300 bajtow, czyli okolo 0.5 kbps. Przy strumieniu 5 Mbps to nic.
 * - dziala na wlasnym watku i przy KAZDYM bledzie milczy. Telemetria nigdy
 *   nie moze ruszyc transmisji wideo.
 * - nie wykonuje zadnych testow predkosci - to zabieraloby pasmo transmisji.
 *   Zamiast tego raportuje zapas lacza widziany przez SRT.
 */
class Telemetry(
    private val context: Context,
    private val service: StreamService
) {

    companion object {
        private const val TAG = "Telemetry"
        private const val INTERVAL_MS = 5000L
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false

    /** Najwyzszy bitrate utrzymany bez zadlawienia - realna miara mozliwosci lacza */
    private var peakBitrateKbps = 0L

    private val task = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                send()
            } catch (e: Exception) {
                Log.d(TAG, "wysylka nieudana (ignoruje): ${e.message}")
            }
            handler?.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        val s = Settings(context)
        if (!s.telemetryEnabled || s.telemetryUrl().isBlank()) return
        running = true
        peakBitrateKbps = 0
        thread = HandlerThread("telemetry").apply { start() }
        handler = Handler(thread!!.looper)
        handler?.post(task)
    }

    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------------

    private fun send() {
        val s = Settings(context)
        val url = s.telemetryUrl()
        if (url.isBlank()) return

        val payload = collect(s).toString()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 4000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(payload) }
            conn.responseCode // wymusza wyslanie
        } finally {
            conn.disconnect()
        }
    }

    private fun collect(s: Settings): JSONObject {
        val bitrate = service.bitrateKbps
        val streaming = service.isStreaming()
        if (streaming && !service.congested && bitrate > peakBitrateKbps) {
            peakBitrateKbps = bitrate
        }

        val battery = readBattery()
        val signal = readSignal()

        return JSONObject().apply {
            put("id", s.streamId)
            put("name", s.cameraName)
            put("status", service.status)
            put("streaming", streaming)
            put("uptime", service.uptimeSeconds)

            put("bitrate", bitrate)
            put("bitrateSet", s.bitrateKbps)
            put("peak", peakBitrateKbps)
            put("dropped", service.droppedFrames)
            put("reconnects", service.reconnects)
            put("congested", service.congested)
            put("queue", service.queueItems)
            put("latency", s.latency)

            put("battery", battery.first)
            put("charging", battery.second)
            put("temp", battery.third)

            put("rsrp", signal.rsrp)
            put("bars", signal.bars)
            put("net", signal.network)
            put("upEstimate", upstreamEstimateKbps())

            put("res", "${s.width}x${s.height}")
            put("fps", s.fps)
            put("codec", s.codec)
            put("src", if (s.videoSource == "UVC") "USB" else "telefon")
            put("app", appVersion())
            put("ts", System.currentTimeMillis())
        }
    }

    /** poziom %, czy laduje, temperatura °C */
    private fun readBattery(): Triple<Int, Boolean, Double> {
        return try {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            Triple(if (level >= 0) level * 100 / scale else -1, plugged, temp)
        } catch (e: Exception) {
            Triple(-1, false, 0.0)
        }
    }

    private data class Signal(val rsrp: Int, val bars: Int, val network: String)

    /**
     * Sila sygnalu serwujacej stacji. Przy dwoch kartach SIM albo przy
     * ograniczeniach producenta moze byc niedostepna - wtedy zwracamy zera.
     */
    private fun readSignal(): Signal {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            var rsrp = 0
            var bars = -1

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val ss = tm.signalStrength
                bars = ss?.level ?: -1
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cells: List<CellSignalStrength> = ss?.cellSignalStrengths ?: emptyList()
                    rsrp = cells.firstOrNull()?.dbm ?: 0
                }
            }

            val net = try {
                when (tm.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_HSPAP,
                    TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                    TelephonyManager.NETWORK_TYPE_UNKNOWN -> wifiOrUnknown()
                    else -> wifiOrUnknown()
                }
            } catch (e: SecurityException) {
                wifiOrUnknown()
            }

            Signal(rsrp, bars, net)
        } catch (e: Exception) {
            Signal(0, -1, wifiOrUnknown())
        }
    }

    private fun wifiOrUnknown(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "brak"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "komorka"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "kabel"
                else -> "?"
            }
        } catch (e: Exception) { "?" }
    }

    /** Szacunek modemu, orientacyjny - nie kosztuje ani bajtu transferu */
    private fun upstreamEstimateKbps(): Int {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)?.linkUpstreamBandwidthKbps ?: 0
        } catch (e: Exception) { 0 }
    }

    private fun appVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
    }
}

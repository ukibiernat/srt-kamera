package pl.srtkam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.extrasources.CameraUvcSource
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.BitrateAdapter

/**
 * Serwis pierwszoplanowy - trzyma strumien przy zyciu takze przy zgaszonym ekranie.
 *
 * Kolejnosc wywolan wymagana przez biblioteke:
 *   1. prepareVideo/prepareAudio  (NIE wolno gdy podglad juz dziala)
 *   2. startPreview
 *   3. startStream
 */
class StreamService : Service(), ConnectChecker {

    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "srt_stream"
        private const val NOTIFICATION_ID = 1001
        var instance: StreamService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): StreamService = this@StreamService
    }

    private val binder = LocalBinder()
    private lateinit var settings: Settings
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    var genericStream: GenericStream? = null
        private set

    private var bitrateAdapter: BitrateAdapter? = null
    private var prepared = false

    var status: String = "Zatrzymany"
        private set
    var currentBitrateKbps: Long = 0
        private set
    var droppedFrames: Long = 0
        private set

    /** Activity podpina sie tu, zeby odswiezac ekran */
    var listener: ((String, Long, Long) -> Unit)? = null

    private var reconnecting = false
    private var reconnectDelayMs = 1000L

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        createNotificationChannel()
        buildStream()
    }

    // ---------------------------------------------------------------
    // Budowa strumienia
    // ---------------------------------------------------------------

    private fun buildStream() {
        val videoSource = when (settings.videoSource) {
            "UVC" -> CameraUvcSource()
            else -> Camera2Source(applicationContext)
        }
        val audioSource = if (settings.audioEnabled) MicrophoneSource() else NoAudioSource()

        genericStream = GenericStream(applicationContext, this, videoSource, audioSource).apply {
            setVideoCodec(if (settings.codec == "H265") VideoCodec.H265 else VideoCodec.H264)
            getStreamClient().setReTries(10)
        }
        prepared = false
    }

    /**
     * Przygotowuje enkodery. Musi byc wywolane ZANIM ruszy podglad.
     * Zwraca komunikat bledu albo null gdy OK.
     */
    private fun prepare(): String? {
        val stream = genericStream ?: return "Strumien nie zainicjalizowany"
        if (prepared) return null
        return try {
            val videoOk = stream.prepareVideo(
                settings.width,
                settings.height,
                settings.bitrateKbps * 1000,
                settings.fps,
                settings.keyframeInterval
            )
            if (!videoOk) return "Enkoder wideo odrzucil ustawienia (sprobuj nizszej rozdzielczosci)"

            if (settings.audioEnabled) {
                val audioOk = stream.prepareAudio(
                    settings.audioSampleRate, true, settings.audioBitrateKbps * 1000
                )
                if (!audioOk) return "Enkoder audio odrzucil ustawienia"
            }
            prepared = true
            null
        } catch (e: Exception) {
            Log.e(TAG, "prepare error", e)
            "Blad przygotowania: ${e.message}"
        }
    }

    /**
     * Przygotowuje enkodery i uruchamia podglad. Wolane przez Activity gdy
     * powierzchnia rysowania jest gotowa.
     */
    fun prepareAndPreview(view: SurfaceView): String? {
        val stream = genericStream ?: return "Brak strumienia"

        val error = prepare()
        if (error != null) return error
        if (stream.isOnPreview) return null

        return try {
            // autoHandle = biblioteka sama pilnuje cyklu zycia powierzchni rysowania
            stream.startPreview(view, true)
            null
        } catch (e: Exception) {
            Log.e(TAG, "preview error", e)
            "Blad podgladu: ${e.message}"
        }
    }

    fun stopPreview() {
        genericStream?.let { if (it.isOnPreview) it.stopPreview(true) }
    }

    fun startStream(): String? {
        val stream = genericStream ?: return "Brak strumienia"
        if (stream.isStreaming) return null
        if (!settings.isConfigured()) return "Uzupelnij adres serwera w ustawieniach"
        if (!prepared) {
            val error = prepare()
            if (error != null) return error
        }

        acquireWakeLock()
        startForegroundNotification()

        if (settings.adaptiveBitrate) {
            bitrateAdapter = BitrateAdapter { bitrate ->
                val floor = settings.minBitrateKbps * 1000
                genericStream?.setVideoBitrateOnFly(if (bitrate < floor) floor else bitrate)
            }.apply { setMaxBitrate(settings.bitrateKbps * 1000) }
        }

        reconnecting = false
        reconnectDelayMs = 1000L

        return try {
            stream.startStream(settings.buildUrl())
            updateStatus("Laczenie...")
            null
        } catch (e: Exception) {
            Log.e(TAG, "start error", e)
            releaseWakeLock()
            "Blad startu: ${e.message}"
        }
    }

    fun stopStream() {
        reconnecting = false
        genericStream?.let { if (it.isStreaming) it.stopStream() }
        bitrateAdapter = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateStatus("Zatrzymany")
    }

    fun isStreaming(): Boolean = genericStream?.isStreaming == true

    /** Po zmianie ustawien - przebudowa strumienia od zera */
    fun rebuild() {
        stopStream()
        stopPreview()
        genericStream?.release()
        genericStream = null
        settings = Settings(this)
        buildStream()
    }

    // ---------------------------------------------------------------
    // Callbacki polaczenia
    // ---------------------------------------------------------------

    override fun onConnectionStarted(url: String) {
        updateStatus("Laczenie...")
    }

    override fun onConnectionSuccess() {
        reconnecting = false
        reconnectDelayMs = 1000L
        updateStatus("NADAJE")
    }

    override fun onConnectionFailed(reason: String) {
        Log.w(TAG, "connection failed: $reason")
        if (settings.autoReconnect) {
            reconnecting = true
            val delay = reconnectDelayMs
            updateStatus("Ponawiam za ${delay / 1000}s")
            // narastajace opoznienie: 1s, 2s, 5s, potem co 10s
            reconnectDelayMs = when (delay) {
                1000L -> 2000L
                2000L -> 5000L
                else -> 10000L
            }
            handler.post {
                try {
                    genericStream?.getStreamClient()?.reTry(delay, reason, null)
                } catch (e: Exception) {
                    Log.e(TAG, "retry error", e)
                }
            }
        } else {
            updateStatus("Blad: $reason")
            stopStream()
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        currentBitrateKbps = bitrate / 1000
        val client = genericStream?.getStreamClient()
        val congestion = try { client?.hasCongestion() ?: false } catch (e: Exception) { false }
        droppedFrames = try { client?.getDroppedVideoFrames() ?: 0L } catch (e: Exception) { 0L }
        bitrateAdapter?.adaptBitrate(bitrate, congestion)
        notifyListener()
    }

    override fun onDisconnect() {
        if (!reconnecting) updateStatus("Rozlaczony")
    }

    override fun onAuthError() {
        updateStatus("Blad autoryzacji")
    }

    override fun onAuthSuccess() {}

    private fun updateStatus(s: String) {
        status = s
        notifyListener()
        if (isStreaming() || reconnecting) updateNotification()
    }

    private fun notifyListener() {
        handler.post { listener?.invoke(status, currentBitrateKbps, droppedFrames) }
    }

    // ---------------------------------------------------------------
    // Powiadomienie i wake lock
    // ---------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Stream SRT", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Powiadomienie aktywnego streamu" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("${settings.cameraName} - $status")
            .setContentText("$currentBitrateKbps kbps")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNotification() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun updateNotification() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SrtKamera::stream")
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onDestroy() {
        stopStream()
        stopPreview()
        genericStream?.release()
        genericStream = null
        instance = null
        super.onDestroy()
    }
}

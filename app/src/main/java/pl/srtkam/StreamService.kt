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
import android.os.SystemClock
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
 * Cala logika nadawania siedzi tutaj, ekran glowny tylko pokazuje stan.
 */
class StreamService : Service(), ConnectChecker {

    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "srt_stream"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_AUTOSTART = "autostart"

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
    private var builtSignature = ""

    // --- stan pokazywany w interfejsie ---
    var status: String = "Zatrzymany"; private set
    var bitrateKbps: Long = 0; private set
    var droppedFrames: Long = 0; private set
    var reconnects: Int = 0; private set
    var lastError: String = ""; private set

    /** Czy kolejka wysylkowa sie zapycha - najlepszy wskaznik braku zapasu lacza */
    var congested: Boolean = false; private set

    /** Ile paczek czeka w kolejce na wyslanie */
    var queueItems: Int = 0; private set

    private var telemetry: Telemetry? = null

    private var streamStartedAt = 0L
    val uptimeSeconds: Long
        get() = if (streamStartedAt == 0L) 0 else (SystemClock.elapsedRealtime() - streamStartedAt) / 1000

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            // start po restarcie telefonu - dajemy chwile na podniesienie sieci
            handler.postDelayed({ startStream() }, 15000)
        }
        return START_STICKY
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
            getStreamClient().setReTries(20)
        }
        builtSignature = settings.buildSignature()
        prepared = false
    }

    private fun prepare(): String? {
        val stream = genericStream ?: return "Strumien nie zainicjalizowany"
        if (prepared) return null
        return try {
            val videoOk = stream.prepareVideo(
                settings.width, settings.height, settings.bitrateKbps * 1000,
                settings.fps, settings.keyframeInterval
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

    fun prepareAndPreview(view: SurfaceView): String? {
        val stream = genericStream ?: return "Brak strumienia"
        val error = prepare()
        if (error != null) return error
        if (stream.isOnPreview) return null
        return try {
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

    fun isPreviewOn(): Boolean = genericStream?.isOnPreview == true

    // ---------------------------------------------------------------
    // Nadawanie
    // ---------------------------------------------------------------

    fun startStream(): String? {
        val stream = genericStream ?: return "Brak strumienia"
        if (stream.isStreaming) return null
        if (!settings.isConfigured()) return "Uzupelnij adres serwera w ustawieniach"

        val error = prepare()
        if (error != null) return error

        acquireWakeLock()
        startForegroundNotification()

        // wieksza kolejka wysylkowa = wieksza odpornosc na chwilowy zanik sieci
        try {
            stream.getStreamClient().resizeCache(settings.cacheSize)
        } catch (e: Exception) {
            Log.w(TAG, "nie udalo sie ustawic kolejki", e)
        }

        bitrateAdapter = if (settings.adaptiveBitrate) {
            BitrateAdapter { bitrate ->
                val floor = settings.minBitrateKbps * 1000
                genericStream?.setVideoBitrateOnFly(if (bitrate < floor) floor else bitrate)
            }.apply { setMaxBitrate(settings.bitrateKbps * 1000) }
        } else null

        reconnecting = false
        reconnectDelayMs = 1000L
        reconnects = 0
        lastError = ""

        return try {
            stream.startStream(settings.buildUrl())
            streamStartedAt = SystemClock.elapsedRealtime()
            updateStatus("Laczenie...")
            telemetry = Telemetry(applicationContext, this).also { it.start() }
            null
        } catch (e: Exception) {
            Log.e(TAG, "start error", e)
            releaseWakeLock()
            "Blad startu: ${e.message}"
        }
    }

    fun stopStream() {
        telemetry?.stop()
        telemetry = null
        reconnecting = false
        streamStartedAt = 0L
        bitrateKbps = 0
        congested = false
        queueItems = 0
        genericStream?.let { if (it.isStreaming) it.stopStream() }
        bitrateAdapter = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateStatus("Zatrzymany")
    }

    fun isStreaming(): Boolean = genericStream?.isStreaming == true

    /** Pelny restart silnika - ratunek gdy cos sie zabuksuje */
    fun restartEngine() {
        val wasStreaming = isStreaming()
        rebuild()
        if (wasStreaming) handler.postDelayed({ startStream() }, 800)
    }

    /**
     * Przebudowuje strumien tylko gdy ustawienia faktycznie sie zmienily.
     * Wolane z ekranu glownego PRZED startem podgladu - kolejnosc ma znaczenie,
     * bo Android wznawia ekran glowny zanim zamknie ekran ustawien.
     */
    fun rebuildIfNeeded(): Boolean {
        val current = Settings(this).buildSignature()
        if (current == builtSignature) return false
        rebuild()
        return true
    }

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

    override fun onConnectionStarted(url: String) = updateStatus("Laczenie...")

    override fun onConnectionSuccess() {
        reconnecting = false
        reconnectDelayMs = 1000L
        lastError = ""
        if (streamStartedAt == 0L) streamStartedAt = SystemClock.elapsedRealtime()
        updateStatus("NADAJE")
    }

    override fun onConnectionFailed(reason: String) {
        Log.w(TAG, "connection failed: $reason")
        lastError = reason
        if (settings.autoReconnect) {
            reconnecting = true
            reconnects++
            val delay = reconnectDelayMs
            updateStatus("Wznawiam za ${delay / 1000}s")
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
            updateStatus("Blad polaczenia")
            stopStream()
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        bitrateKbps = bitrate / 1000
        val client = genericStream?.getStreamClient()
        congested = try { client?.hasCongestion() ?: false } catch (e: Exception) { false }
        queueItems = try { client?.getItemsInCache() ?: 0 } catch (e: Exception) { 0 }
        droppedFrames = try { client?.getDroppedVideoFrames() ?: 0L } catch (e: Exception) { 0L }
        bitrateAdapter?.adaptBitrate(bitrate, congested)
    }

    override fun onDisconnect() {
        if (!reconnecting) updateStatus("Rozlaczony")
    }

    override fun onAuthError() = updateStatus("Blad hasla")
    override fun onAuthSuccess() {}

    private fun updateStatus(s: String) {
        status = s
        if (isStreaming() || reconnecting) updateNotification()
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
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("${settings.cameraName} — $status")
            .setContentText("$bitrateKbps kbps")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNotification() = startForeground(NOTIFICATION_ID, buildNotification())

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
        if (wakeLock?.isHeld == false) wakeLock?.acquire(16 * 60 * 60 * 1000L)
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

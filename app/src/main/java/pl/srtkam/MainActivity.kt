package pl.srtkam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var txtStatus: TextView
    private lateinit var txtName: TextView
    private lateinit var txtStats: TextView
    private lateinit var txtUptime: TextView
    private lateinit var txtPreviewOff: TextView
    private lateinit var dot: View
    private lateinit var btnStream: Button
    private lateinit var btnSettings: Button
    private lateinit var btnPreview: Button
    private lateinit var btnLock: Button
    private lateinit var btnCamera: Button
    private lateinit var lockOverlay: LinearLayout
    private lateinit var cameraPanel: LinearLayout
    private var cameraControls: CameraControls? = null

    private var service: StreamService? = null
    private var bound = false

    /** Podglad kosztuje baterie i cieplo - operator moze go wylaczyc po wykadrowaniu */
    private var previewEnabled = true
    private var locked = false
    private var autoStartDone = false

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as StreamService.LocalBinder).getService()
            bound = true
            service?.rebuildIfNeeded()
            if (previewEnabled) startPreview()
            refresh()
            maybeAutoStart()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { !it }) toast("Bez uprawnien aplikacja nie zadziala")
        else startPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        txtStatus = findViewById(R.id.txtStatus)
        txtName = findViewById(R.id.txtName)
        txtStats = findViewById(R.id.txtStats)
        txtUptime = findViewById(R.id.txtUptime)
        txtPreviewOff = findViewById(R.id.txtPreviewOff)
        dot = findViewById(R.id.dot)
        btnStream = findViewById(R.id.btnStream)
        btnSettings = findViewById(R.id.btnSettings)
        btnPreview = findViewById(R.id.btnPreview)
        btnLock = findViewById(R.id.btnLock)
        btnCamera = findViewById(R.id.btnCamera)
        lockOverlay = findViewById(R.id.lockOverlay)
        cameraPanel = findViewById(R.id.cameraPanel)
        cameraControls = CameraControls(this, cameraPanel).also { it.build() }

        btnStream.setOnClickListener { toggleStream() }
        btnStream.setOnLongClickListener { restartEngine(); true }
        btnPreview.setOnClickListener { togglePreview() }
        btnLock.setOnClickListener { setLocked(true) }
        btnCamera.setOnClickListener { toggleCameraPanel() }
        setupTapToFocus()
        lockOverlay.setOnLongClickListener { setLocked(false); true }

        btnSettings.setOnClickListener {
            if (service?.isStreaming() == true) {
                toast("Zatrzymaj nadawanie przed zmiana ustawien")
            } else {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        requestPermissions()
    }

    private fun requestPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, StreamService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        service?.rebuildIfNeeded()
        if (previewEnabled) startPreview()
        ui.post(ticker)
        maybeAutoStart()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    /** Punkt bez obslugi - nadawanie rusza samo po otwarciu aplikacji */
    private fun maybeAutoStart() {
        if (autoStartDone) return
        val svc = service ?: return
        val s = Settings(this)
        if (!s.autoStart || !s.isConfigured() || svc.isStreaming()) return
        autoStartDone = true
        ui.postDelayed({
            val error = svc.startStream()
            if (error != null) toast(error)
            else if (s.previewOffOnStart && previewEnabled) togglePreview()
        }, 1500)
    }

    /** Panel dziala tylko dla kamery wbudowanej - przy grabberze nie ma czego regulowac */
    private fun toggleCameraPanel() {
        if (Settings(this).videoSource == "UVC") {
            toast("Ustawienia obrazu przy grabberze HDMI robi sie na kamerze, nie w telefonie")
            return
        }
        if (service?.isPreviewOn() != true && service?.isStreaming() != true) {
            toast("Najpierw wlacz podglad albo nadawanie")
            return
        }
        val cc = cameraControls ?: return
        cc.show(!cc.isVisible())
    }

    /** Dotkniecie obrazu ustawia ostrosc i pomiar swiatla w tym punkcie */
    private fun setupTapToFocus() {
        surfaceView.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP &&
                Settings(this).videoSource != "UVC"
            ) {
                val cam = service?.genericStream?.videoSource
                        as? com.pedro.encoder.input.sources.video.Camera2Source
                try {
                    cam?.tapToFocus(view, event)
                    cam?.tapToMeterExposure(view, event)
                } catch (_: Exception) {}
                view.performClick()
            }
            true
        }
    }

    private fun startPreview() {
        val svc = service ?: return
        if (!previewEnabled) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val error = svc.prepareAndPreview(surfaceView)
        if (error != null) toast(error)
        else ui.postDelayed({ if (cameraControls?.isVisible() == true) cameraControls?.refresh() }, 900)
    }

    /**
     * Wlacza i wylacza podglad. Wylaczony podglad oszczedza baterie i obniza
     * temperature - nadawanie idzie dalej, mozna tez zgasic ekran.
     */
    private fun togglePreview() {
        previewEnabled = !previewEnabled
        if (previewEnabled) {
            txtPreviewOff.visibility = View.GONE
            surfaceView.visibility = View.VISIBLE
            btnPreview.text = getString(R.string.preview_off)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            surfaceView.post { startPreview() }
        } else {
            service?.stopPreview()
            surfaceView.visibility = View.GONE
            txtPreviewOff.visibility = View.VISIBLE
            btnPreview.text = getString(R.string.preview_on)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Blokada dotyku - zeby nikt nie ubil nadawania przypadkiem */
    private fun setLocked(value: Boolean) {
        locked = value
        lockOverlay.visibility = if (locked) View.VISIBLE else View.GONE
        if (locked) cameraControls?.show(false)
        if (locked) toast("Ekran zablokowany. Przytrzymaj palec, aby odblokowac.")
    }

    private fun restartEngine() {
        val svc = service ?: return
        toast("Restartuje silnik...")
        svc.restartEngine()
        ui.postDelayed({ if (previewEnabled) startPreview() }, 1200)
    }

    private fun toggleStream() {
        val svc = service ?: return
        if (svc.isStreaming()) {
            svc.stopStream()
        } else {
            val error = svc.startStream()
            if (error != null) toast(error)
            else if (Settings(this).previewOffOnStart && previewEnabled) togglePreview()
        }
        refresh()
    }

    // ---------------------------------------------------------------
    // Odswiezanie ekranu co sekunde
    // ---------------------------------------------------------------

    private fun refresh() {
        val s = Settings(this)
        val svc = service
        txtName.text = s.cameraName
        btnStream.text = if (svc?.isStreaming() == true) getString(R.string.stop) else getString(R.string.start)

        val status = svc?.status ?: "Zatrzymany"
        txtStatus.text = status
        val color = when {
            status == "NADAJE" -> 0xFF2ECC71.toInt()
            status.startsWith("Wznawiam") || status == "Laczenie..." -> 0xFFF39C12.toInt()
            status == "Zatrzymany" -> 0xFFBDC3C7.toInt()
            else -> 0xFFE74C3C.toInt()
        }
        txtStatus.setTextColor(color)
        dot.setBackgroundColor(color)

        txtUptime.text = if (svc?.isStreaming() == true) formatUptime(svc.uptimeSeconds) else ""

        txtStats.text = if (svc?.isStreaming() == true) {
            buildString {
                append("${svc.bitrateKbps} kbps")
                append("  •  bufor ${s.latency} ms")
                append("  •  zgubione klatki: ${svc.droppedFrames}")
                if (svc.reconnects > 0) append("  •  wznowienia: ${svc.reconnects}")
                append("  •  ${battery()}")
            }
        } else {
            buildString {
                append("${s.width}x${s.height}@${s.fps}")
                append("  •  ${s.bitrateKbps} kbps ${s.codec}")
                append("  •  ${if (s.videoSource == "UVC") "kamera USB" else "kamera telefonu"}")
                append("  •  bufor ${s.latency} ms")
                if (s.host.isBlank()) append("  •  BRAK ADRESU SERWERA")
                append("  •  ${battery()}")
            }
        }
    }

    private fun formatUptime(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** Poziom baterii i temperatura - kluczowe przy dlugim nadawaniu w terenie */
    private fun battery(): String {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val pct = if (level >= 0) level * 100 / scale else 0
            val temp = tempTenths / 10.0
            "bateria $pct%  •  ${"%.0f".format(temp)}°C"
        } catch (e: Exception) {
            ""
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Chowa pasek stanu i pasek nawigacji */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onBackPressed() {
        if (locked) {
            toast("Ekran zablokowany")
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}

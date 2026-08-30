package pl.srtkam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.library.view.OpenGlView

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var openGlView: OpenGlView
    private lateinit var txtStatus: TextView
    private lateinit var txtName: TextView
    private lateinit var txtStats: TextView
    private lateinit var btnStream: Button
    private lateinit var btnSettings: Button

    private var service: StreamService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as StreamService.LocalBinder).getService()
            bound = true
            service?.listener = { status, bitrate, dropped -> render(status, bitrate, dropped) }
            if (openGlView.holder.surface?.isValid == true) service?.startPreview(openGlView)
            refreshLabels()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { !it }) {
            toast("Bez uprawnien aplikacja nie zadziala")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        txtStatus = findViewById(R.id.txtStatus)
        txtName = findViewById(R.id.txtName)
        txtStats = findViewById(R.id.txtStats)
        btnStream = findViewById(R.id.btnStream)
        btnSettings = findViewById(R.id.btnSettings)

        openGlView.holder.addCallback(this)

        btnStream.setOnClickListener { toggleStream() }
        btnSettings.setOnClickListener {
            if (service?.isStreaming() == true) {
                toast("Zatrzymaj stream przed zmiana ustawien")
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
        refreshLabels()
    }

    override fun onStop() {
        super.onStop()
        service?.listener = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun toggleStream() {
        val svc = service ?: return
        if (svc.isStreaming()) {
            svc.stopStream()
            btnStream.text = getString(R.string.start)
        } else {
            val error = svc.startStream()
            if (error != null) {
                toast(error)
            } else {
                btnStream.text = getString(R.string.stop)
            }
        }
    }

    private fun refreshLabels() {
        val s = Settings(this)
        txtName.text = s.cameraName
        val src = if (s.videoSource == "UVC") "USB" else "wbudowana"
        txtStats.text = "${s.width}x${s.height}@${s.fps} - ${s.bitrateKbps} kbps - $src - bufor ${s.latency} ms"
        btnStream.text = if (service?.isStreaming() == true) getString(R.string.stop) else getString(R.string.start)
    }

    private fun render(status: String, bitrate: Long, dropped: Long) {
        txtStatus.text = status
        txtStatus.setTextColor(
            when {
                status == "NADAJE" -> 0xFF2ECC71.toInt()
                status.startsWith("Ponawiam") || status == "Laczenie..." -> 0xFFF39C12.toInt()
                status == "Zatrzymany" -> 0xFFBDC3C7.toInt()
                else -> 0xFFE74C3C.toInt()
            }
        )
        if (service?.isStreaming() == true) {
            val s = Settings(this)
            txtStats.text = "$bitrate kbps - zgubione klatki: $dropped - bufor ${s.latency} ms"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // --- podglad ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        service?.startPreview(openGlView)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (service?.isStreaming() != true) service?.stopPreview()
    }
}

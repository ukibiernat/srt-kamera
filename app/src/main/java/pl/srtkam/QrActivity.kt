package pl.srtkam

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Przenoszenie konfiguracji miedzy telefonami kodem QR.
 * Jeden telefon ustawiasz recznie, pokazujesz kod, reszta go skanuje.
 * Numer punktu NIE jest przenoszony - kazdy telefon zachowuje wlasny.
 */
class QrActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SHOW = "show"
        const val MODE_SCAN = "scan"
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) {
            finish()
            return@registerForActivityResult
        }
        val error = Settings(this).applyQrJson(contents)
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                this,
                "Konfiguracja wczytana. Sprawdz numer punktu w ustawieniach.",
                Toast.LENGTH_LONG
            ).show()
            StreamService.instance?.rebuild()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_SCAN -> startScan()
            else -> showQr()
        }
    }

    private fun startScan() {
        scanLauncher.launch(
            ScanOptions().apply {
                setPrompt("Wyceluj w kod QR z telefonu wzorcowego")
                setBeepEnabled(false)
                setOrientationLocked(false)
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            }
        )
    }

    private fun showQr() {
        val s = Settings(this)
        if (!s.isConfigured()) {
            Toast.makeText(this, "Najpierw uzupelnij adres serwera", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val info = TextView(this).apply {
            text = "Zeskanuj ten kod pozostalymi telefonami.\n" +
                    "Przeniesie wszystkie ustawienia oprocz numeru punktu."
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        root.addView(info)

        try {
            val bitmap = BarcodeEncoder().encodeBitmap(
                s.toQrJson(), BarcodeFormat.QR_CODE, 900, 900
            )
            val image = ImageView(this).apply {
                setImageBitmap(bitmap)
                setBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
            }
            root.addView(image)
        } catch (e: Exception) {
            Toast.makeText(this, "Nie udalo sie zbudowac kodu: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val summary = TextView(this).apply {
            text = "Serwer: ${s.host}:${s.port}\n" +
                    "Bufor: ${s.latency} ms\n" +
                    "Obraz: ${s.width}x${s.height}@${s.fps}, ${s.bitrateKbps} kbps, ${s.codec}"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 24)
        }
        root.addView(summary)

        val close = Button(this).apply {
            text = "Zamknij"
            setOnClickListener { finish() }
        }
        root.addView(close)

        setContentView(root)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

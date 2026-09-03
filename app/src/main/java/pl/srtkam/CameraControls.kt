package pl.srtkam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.pedro.encoder.input.sources.video.Camera2Source

/**
 * Panel sterowania kamera wbudowana: wybor obiektywu, zoom, ekspozycja,
 * autofokus, blokada ekspozycji, stabilizacja i latarka.
 *
 * Dziala tylko dla kamery wbudowanej. Przy grabberze HDMI te parametry
 * ustawia sie na samej kamerze, telefon nie ma na nie wplywu.
 *
 * Uwaga: wiekszosc metod biblioteki dziala dopiero gdy kamera JEST uruchomiona,
 * dlatego ustawienia nakladamy z opoznieniem po starcie podgladu.
 */
class CameraControls(
    private val activity: AppCompatActivity,
    private val container: LinearLayout
) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(activity)

    private lateinit var lensRow: LinearLayout
    private lateinit var zoomBar: SeekBar
    private lateinit var zoomLabel: TextView
    private lateinit var expBar: SeekBar
    private lateinit var expLabel: TextView
    private lateinit var btnAf: Button
    private lateinit var btnAeLock: Button
    private lateinit var btnStab: Button
    private lateinit var btnTorch: Button

    private var zoomMin = 1f
    private var zoomMax = 1f
    private var expMin = 0
    private var expMax = 0
    private var expStep = 1.0 / 6.0

    private fun camera(): Camera2Source? =
        StreamService.instance?.genericStream?.videoSource as? Camera2Source

    // ------------------------------------------------------------------
    // Budowa panelu
    // ------------------------------------------------------------------

    fun build() {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        container.setBackgroundColor(0xCC000000.toInt())
        container.setPadding(dp(12), dp(10), dp(12), dp(10))

        lensRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        container.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(lensRow)
        })

        // --- zoom ---
        zoomLabel = label("Zoom 1.0x")
        container.addView(zoomLabel)
        zoomBar = SeekBar(activity).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    applyZoom(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(zoomBar)

        // --- ekspozycja ---
        expLabel = label("Ekspozycja 0.0 EV")
        container.addView(expLabel)
        expBar = SeekBar(activity).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    applyExposure(expMin + value)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(expBar)

        // --- przelaczniki ---
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        btnAf = smallButton("Autofokus") { toggleAutofocus() }
        btnAeLock = smallButton("Blokada eksp.") { toggleExposureLock() }
        btnStab = smallButton("Stabilizacja") { toggleStabilization() }
        btnTorch = smallButton("Latarka") { toggleTorch() }
        row.addView(btnAf); row.addView(btnAeLock); row.addView(btnStab); row.addView(btnTorch)
        container.addView(row)

        container.addView(TextView(activity).apply {
            text = "Dotknij obrazu, aby ustawić ostrość i pomiar w tym punkcie"
            setTextColor(0xFF95A5A6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
        })
    }

    /**
     * Odczytuje mozliwosci kamery i przywraca zapisane wartosci.
     * Wolane po starcie podgladu - wczesniej biblioteka zignoruje ustawienia.
     */
    fun refresh() {
        val cam = camera() ?: return

        // zakres zoomu
        try {
            val range = cam.getZoomRange()
            zoomMin = range.lower
            zoomMax = range.upper
        } catch (e: Exception) {
            zoomMin = 1f; zoomMax = 1f
        }

        // zakres kompensacji ekspozycji z parametrow aparatu
        try {
            val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ch = cm.getCameraCharacteristics(cam.getCurrentCameraId())
            val r = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            expMin = r?.lower ?: 0
            expMax = r?.upper ?: 0
            expStep = if (step != null && step.denominator != 0)
                step.numerator.toDouble() / step.denominator.toDouble() else 1.0 / 6.0
        } catch (e: Exception) {
            expMin = 0; expMax = 0; expStep = 1.0 / 6.0
        }

        expBar.max = (expMax - expMin).coerceAtLeast(0)
        expBar.isEnabled = expMax > expMin
        zoomBar.isEnabled = zoomMax > zoomMin

        buildLensButtons()

        // przywroc zapisane wartosci
        val savedExp = prefs.getInt("cam_exposure", 0).coerceIn(expMin, expMax)
        val savedZoom = prefs.getFloat("cam_zoom", zoomMin).coerceIn(zoomMin, zoomMax)
        applyExposure(savedExp)
        expBar.progress = savedExp - expMin
        applyZoomValue(savedZoom)
        zoomBar.progress = zoomToProgress(savedZoom)

        if (prefs.getBoolean("cam_stab", false)) {
            try { cam.enableVideoStabilization() } catch (_: Exception) {}
        }
        if (!prefs.getBoolean("cam_af", true)) {
            try { cam.disableAutoFocus() } catch (_: Exception) {}
        }
        updateToggleLabels()
    }

    fun show(visible: Boolean) {
        container.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) refresh()
    }

    fun isVisible() = container.visibility == View.VISIBLE

    // ------------------------------------------------------------------
    // Obiektywy
    // ------------------------------------------------------------------

    private fun buildLensButtons() {
        lensRow.removeAllViews()
        val cam = camera() ?: return
        val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        data class Lens(val id: String, val focal: Float, val front: Boolean)

        val lenses = mutableListOf<Lens>()
        try {
            for (id in cam.camerasAvailable()) {
                val ch = cm.getCameraCharacteristics(id)
                val front = ch.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull() ?: 0f
                lenses.add(Lens(id, focal, front))
            }
        } catch (e: Exception) {
            return
        }

        val back = lenses.filter { !it.front }.sortedBy { it.focal }
        val front = lenses.filter { it.front }
        val current = try { cam.getCurrentCameraId() } catch (e: Exception) { "" }

        fun labelFor(l: Lens, index: Int, total: Int): String {
            val mm = if (l.focal > 0) " %.1fmm".format(l.focal) else ""
            val name = when {
                total == 1 -> "Tylna"
                index == 0 -> "Szeroki"
                index == total - 1 -> "Tele"
                else -> "Główny"
            }
            return name + mm
        }

        back.forEachIndexed { i, l ->
            lensRow.addView(lensButton(labelFor(l, i, back.size), l.id, l.id == current))
        }
        front.forEach { l ->
            lensRow.addView(lensButton("Przednia", l.id, l.id == current))
        }
    }

    private fun lensButton(text: String, id: String, active: Boolean): Button =
        Button(activity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = dp(90)
            setTextColor(if (active) 0xFF2ECC71.toInt() else 0xFFECF0F1.toInt())
            setOnClickListener {
                val cam = camera()
                if (cam == null) {
                    toast("Kamera nieaktywna")
                    return@setOnClickListener
                }
                try {
                    cam.openCameraId(id)
                    container.postDelayed({ refresh() }, 700)
                } catch (e: Exception) {
                    toast("Ten obiektyw nie jest dostępny: ${e.message}")
                }
            }
        }

    // ------------------------------------------------------------------
    // Zoom i ekspozycja
    // ------------------------------------------------------------------

    private fun zoomToProgress(value: Float): Int {
        if (zoomMax <= zoomMin) return 0
        return (((value - zoomMin) / (zoomMax - zoomMin)) * 100f).toInt().coerceIn(0, 100)
    }

    private fun applyZoom(progress: Int) {
        val value = zoomMin + (zoomMax - zoomMin) * (progress / 100f)
        applyZoomValue(value)
    }

    private fun applyZoomValue(value: Float) {
        try {
            camera()?.setZoom(value)
            prefs.edit().putFloat("cam_zoom", value).apply()
        } catch (_: Exception) {}
        zoomLabel.text = "Zoom %.1fx  (maks. %.1fx)".format(value, zoomMax)
    }

    private fun applyExposure(level: Int) {
        try {
            camera()?.setExposure(level)
            prefs.edit().putInt("cam_exposure", level).apply()
        } catch (_: Exception) {}
        val ev = level * expStep
        expLabel.text = if (expMax > expMin)
            "Ekspozycja %+.1f EV".format(ev) else "Ekspozycja — brak regulacji"
    }

    // ------------------------------------------------------------------
    // Przelaczniki
    // ------------------------------------------------------------------

    private fun toggleAutofocus() {
        val cam = camera() ?: return
        try {
            if (cam.isAutoFocusEnabled()) cam.disableAutoFocus() else cam.enableAutoFocus()
            prefs.edit().putBoolean("cam_af", cam.isAutoFocusEnabled()).apply()
        } catch (_: Exception) {}
        updateToggleLabels()
    }

    private fun toggleExposureLock() {
        val cam = camera() ?: return
        try {
            if (cam.isExposureLockEnabled) cam.disableExposureLock() else cam.enableExposureLock()
        } catch (_: Exception) {}
        updateToggleLabels()
    }

    private fun toggleStabilization() {
        val cam = camera() ?: return
        try {
            if (cam.isVideoStabilizationEnabled) cam.disableVideoStabilization()
            else if (!cam.enableVideoStabilization()) toast("Ten telefon nie ma stabilizacji obrazu")
            prefs.edit().putBoolean("cam_stab", cam.isVideoStabilizationEnabled).apply()
        } catch (_: Exception) {}
        updateToggleLabels()
    }

    private fun toggleTorch() {
        val cam = camera() ?: return
        try {
            if (cam.isLanternEnabled()) cam.disableLantern() else cam.enableLantern()
        } catch (e: Exception) {
            toast("Latarka niedostępna")
        }
        updateToggleLabels()
    }

    private fun updateToggleLabels() {
        val cam = camera()
        fun mark(b: Button, on: Boolean, name: String) {
            b.text = name
            b.setTextColor(if (on) 0xFF2ECC71.toInt() else 0xFFECF0F1.toInt())
        }
        try {
            mark(btnAf, cam?.isAutoFocusEnabled() == true, "Autofokus")
            mark(btnAeLock, cam?.isExposureLockEnabled == true, "Blokada eksp.")
            mark(btnStab, cam?.isVideoStabilizationEnabled == true, "Stabilizacja")
            mark(btnTorch, cam?.isLanternEnabled() == true, "Latarka")
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------

    private fun label(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(0xFFECF0F1.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(8), 0, 0)
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}

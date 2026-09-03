package pl.srtkam

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {

    companion object {
        /** Ustawiane po wczytaniu konfiguracji z kodu QR */
        var needsReload = false
    }

    override fun onResume() {
        super.onResume()
        if (needsReload) {
            needsReload = false
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("qr_scan")?.setOnPreferenceClickListener {
                openQr(QrActivity.MODE_SCAN); true
            }
            findPreference<Preference>("qr_show")?.setOnPreferenceClickListener {
                openQr(QrActivity.MODE_SHOW); true
            }
        }

        private fun openQr(mode: String) {
            startActivity(
                Intent(requireContext(), QrActivity::class.java)
                    .putExtra(QrActivity.EXTRA_MODE, mode)
            )
        }

    }
}

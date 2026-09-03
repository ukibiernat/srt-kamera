package pl.srtkam

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {

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

        override fun onResume() {
            super.onResume()
            // po powrocie ze skanera odswiez wyswietlane wartosci
            preferenceScreen = null
            setPreferencesFromResource(R.xml.preferences, null)
            findPreference<Preference>("qr_scan")?.setOnPreferenceClickListener {
                openQr(QrActivity.MODE_SCAN); true
            }
            findPreference<Preference>("qr_show")?.setOnPreferenceClickListener {
                openQr(QrActivity.MODE_SHOW); true
            }
        }
    }
}

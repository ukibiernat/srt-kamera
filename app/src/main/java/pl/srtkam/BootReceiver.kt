package pl.srtkam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Po restarcie telefonu probuje wznowic nadawanie.
 *
 * Uwaga: Android 14 i nowszy potrafi zablokowac uruchomienie uslugi
 * korzystajacej z kamery bez udzialu uzytkownika. Dlatego probujemy,
 * a gdy system odmowi - po prostu odpuszczamy i czekamy na otwarcie aplikacji.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings(context).autoStartOnBoot) return

        try {
            val service = Intent(context, StreamService::class.java).apply {
                putExtra(StreamService.EXTRA_AUTOSTART, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (e: Exception) {
            Log.w("BootReceiver", "System nie pozwolil wystartowac po restarcie", e)
        }
    }
}

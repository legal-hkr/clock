package sh.hnet.clock.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import sh.hnet.clock.ClockApplication

/**
 * After a restart of the phone, or an update of the app, the system has dropped the app's
 * notifications: what is still running gets its own back.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> (context.applicationContext as ClockApplication).stopwatch.sync()
        }
    }
}

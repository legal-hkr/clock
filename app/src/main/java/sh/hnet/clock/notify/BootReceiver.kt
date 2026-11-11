package sh.hnet.clock.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import sh.hnet.clock.ClockApplication

/**
 * After a restart of the phone, or an update of the app, the system has dropped the app's
 * notifications and forgotten what was scheduled: what is still running gets both back.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val app = context.applicationContext as ClockApplication
                app.stopwatch.sync()
                // Timers are scheduled anew (the system forgot them) and what ended meanwhile is dealt with
                app.timers.silenceOverdue()
            }
        }
    }
}

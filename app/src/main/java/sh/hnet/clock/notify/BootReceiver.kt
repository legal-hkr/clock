package sh.hnet.clock.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import sh.hnet.clock.ClockApplication

/**
 * What the system forgets or changes under the app:
 * - after a restart of the phone or an update of the app, everything scheduled and every
 *   notification is gone – what is still on or running gets both back. Alarms already when the
 *   phone has started but is not yet unlocked; the rest once it is;
 * - when the clock or the time zone is changed, alarms keep their wall-clock time (SPEC §5.5):
 *   their next moments are worked out anew, and one just jumped over rings at once.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ClockApplication
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> app.alarms.refresh()
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                app.alarms.refresh()
                app.afterUnlock()
                if (app.unlocked) {
                    app.stopwatch.sync()
                    app.timers.silenceOverdue()
                }
            }
        }
    }
}

package sh.hnet.clock.alarm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.MainActivity
import sh.hnet.clock.notify.Notifications
import sh.hnet.clock.ui.Tab
import java.util.Calendar

/**
 * The platform's alarm-clock intents (SPEC §3.8.4, §5): other apps and the system can ask Clock to
 * set an alarm or a timer, or to show the alarms. What they set appears like anything set in the
 * app. Without the "skip UI" extra the app then opens on Alarms or Timer; with it nothing is shown.
 * An intent that names no time (or no length) just opens the destination, where the user sets it.
 *
 * No screen of its own: it does the work and is gone.
 */
class AlarmIntentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ClockApplication
        val request = intent
        val skipUi = request.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)
        val tab = when (request.action) {
            AlarmClock.ACTION_SET_ALARM -> {
                val hour = request.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
                val minute = request.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)
                if (hour in 0..23 && minute in 0..59) {
                    // Calendar's days (Sunday = 1) to ISO's (Monday = 1)
                    val days = request.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS).orEmpty()
                        .filter { it in Calendar.SUNDAY..Calendar.SATURDAY }.map { (it + 5) % 7 + 1 }.toSet()
                    val vibrate = if (request.hasExtra(AlarmClock.EXTRA_VIBRATE)) request.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, true) else null
                    app.alarms.add(hour, minute, request.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty(), days, vibrate)
                }
                Tab.ALARMS
            }
            AlarmClock.ACTION_SET_TIMER -> {
                val seconds = request.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
                if (seconds > 0 && app.unlocked) app.timers.start(seconds * 1000L, request.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty().take(60))
                Tab.TIMER
            }
            else -> Tab.ALARMS
        }
        if (!skipUi || request.action == AlarmClock.ACTION_SHOW_ALARMS) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(Notifications.EXTRA_DESTINATION, tab.name),
            )
        }
        finish()
    }
}

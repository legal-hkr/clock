package sh.hnet.clock.timer

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.notify.Notifications
import sh.hnet.clock.stopwatch.Moment
import sh.hnet.clock.ui.Tab

/**
 * One notification per timer (SPEC §3.8.1), grouped by the system: running, the system counts down
 * by itself with Pause and Add 1 minute; paused, the time left stands with Resume. A timer that
 * ended and went quiet keeps a notification counting up, with Stop. Ringing timers are the ringing
 * service's own notification. On Android 16 and later the running timer that ends first asks to be
 * a live update (§3.8.2).
 */
class TimerNotifications(private val context: Context) {

    fun show(timers: List<Timer>, now: Moment) {
        val manager = Notifications.manager(context)
        val wanted = timers.filter { !it.ringing(now) }
        val ids = wanted.map { idOf(it.id) }.toSet()
        manager.activeNotifications.filter { it.id >= Notifications.ID_TIMER_BASE && it.id !in ids }.forEach { manager.cancel(it.id) }
        if (wanted.isEmpty() || !Notifications.allowed(context)) return
        Notifications.ensureChannels(context)
        val first = wanted.filter { it.running && !it.ended(now) }.minByOrNull { it.remaining(now) }
        wanted.forEach { timer -> manager.notify(idOf(timer.id), build(timer, now, promoted = timer.id == first?.id)) }
    }

    private fun build(timer: Timer, now: Moment, promoted: Boolean): Notification {
        val builder = Notification.Builder(context, Notifications.CHANNEL_TIMERS)
            .setSmallIcon(R.drawable.ic_hourglass_empty)
            .setContentTitle(timer.label.ifEmpty { context.getString(R.string.label_timer_default) })
            .setContentIntent(Notifications.open(context, Tab.TIMER))
            .setCategory(Notification.CATEGORY_ALARM)
            .setGroup(GROUP)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
        when {
            timer.ended(now) -> {
                // Ended and gone quiet: counting up since its end
                builder.setUsesChronometer(true).setWhen(timer.endsAt(now)).setShowWhen(true)
                    .setContentText(context.getString(R.string.label_timer_ended))
                    .addAction(action(R.drawable.ic_stop, R.string.button_stop, TimerReceiver.ACTION_STOP, timer.id))
                    .addAction(action(R.drawable.ic_add, R.string.button_add_minute, TimerReceiver.ACTION_ADD_MINUTE, timer.id))
            }
            timer.running -> {
                builder.setUsesChronometer(true).setChronometerCountDown(true).setWhen(timer.endsAt(now)).setShowWhen(true)
                    .addAction(action(R.drawable.ic_pause, R.string.button_pause, TimerReceiver.ACTION_PAUSE, timer.id))
                    .addAction(action(R.drawable.ic_add, R.string.button_add_minute, TimerReceiver.ACTION_ADD_MINUTE, timer.id))
                // Live updates came with Android 16 QPR1 (API 36.1)
                if (promoted && Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) builder.setRequestPromotedOngoing(true)
            }
            else -> {
                builder.setShowWhen(false)
                    .setSubText(context.getString(R.string.label_paused))
                    .setContentText(TimerFormat.left(timer.remaining(now)))
                    .addAction(action(R.drawable.ic_play_arrow, R.string.button_resume, TimerReceiver.ACTION_RESUME, timer.id))
                    .addAction(action(R.drawable.ic_add, R.string.button_add_minute, TimerReceiver.ACTION_ADD_MINUTE, timer.id))
            }
        }
        return builder.build()
    }

    private fun action(icon: Int, label: Int, action: String, id: Long): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, icon), context.getString(label), intent(context, action, id)).build()

    companion object {
        private const val GROUP = "timers"

        fun idOf(timerId: Long): Int = Notifications.ID_TIMER_BASE + (timerId % 1_000_000).toInt()

        /** A pending broadcast to [TimerReceiver] for one timer (or all, with [id] 0). */
        fun intent(context: Context, action: String, id: Long = 0): PendingIntent = PendingIntent.getBroadcast(
            context,
            (id % 1_000_000).toInt(),
            Intent(context, TimerReceiver::class.java).setAction(action).putExtra(TimerReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** The end of a timer, as scheduled with the system, and the notifications' buttons. Not exported. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timers = (context.applicationContext as ClockApplication).timers
        val id = intent.getLongExtra(EXTRA_ID, 0)
        when (intent.action) {
            ACTION_FIRE -> timers.refresh()
            ACTION_PAUSE -> timers.pause(id)
            ACTION_RESUME -> timers.resume(id)
            ACTION_ADD_MINUTE -> timers.addMinute(id)
            ACTION_STOP -> timers.delete(id)
            ACTION_STOP_ALL -> timers.stopRinging()
        }
    }

    companion object {
        const val EXTRA_ID = "sh.hnet.clock.extra.TIMER_ID"
        const val ACTION_FIRE = "sh.hnet.clock.action.TIMER_FIRE"
        const val ACTION_PAUSE = "sh.hnet.clock.action.TIMER_PAUSE"
        const val ACTION_RESUME = "sh.hnet.clock.action.TIMER_RESUME"
        const val ACTION_ADD_MINUTE = "sh.hnet.clock.action.TIMER_ADD_MINUTE"
        const val ACTION_STOP = "sh.hnet.clock.action.TIMER_STOP"
        const val ACTION_STOP_ALL = "sh.hnet.clock.action.TIMER_STOP_ALL"
    }
}

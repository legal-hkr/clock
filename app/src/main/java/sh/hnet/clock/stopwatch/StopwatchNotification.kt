package sh.hnet.clock.stopwatch

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.notify.Notifications
import sh.hnet.clock.ui.Tab

/**
 * The stopwatch's notification (SPEC §3.8), there while it runs or is paused: running, the system
 * counts the time itself in the header (a chronometer – nothing has to keep the process alive)
 * with Pause and Lap; paused, the time stands in the text with "Paused" in the header, and the
 * buttons are Resume and Reset. Not a live update (§3.8.2): a stopwatch has no end to wait for.
 */
class StopwatchNotification(private val context: Context) {

    fun show(stopwatch: Stopwatch, now: Moment) {
        val manager = Notifications.manager(context)
        if (stopwatch.isZero) {
            manager.cancel(Notifications.ID_STOPWATCH)
            return
        }
        if (!Notifications.allowed(context)) return
        Notifications.ensureChannels(context)
        val elapsed = stopwatch.elapsed(now)
        val builder = Notification.Builder(context, Notifications.CHANNEL_STOPWATCH)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(context.getString(R.string.label_nav_stopwatch))
            .setContentIntent(Notifications.open(context, Tab.STOPWATCH))
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
        if (stopwatch.running) {
            builder.setUsesChronometer(true).setWhen(now.wall - elapsed).setShowWhen(true)
            if (stopwatch.laps.isNotEmpty()) builder.setContentText(context.getString(R.string.label_lap_n, stopwatch.laps.size + 1))
            builder.addAction(action(R.drawable.ic_pause, R.string.button_pause, StopwatchReceiver.ACTION_PAUSE))
            if (stopwatch.canLap) builder.addAction(action(R.drawable.ic_flag, R.string.button_lap, StopwatchReceiver.ACTION_LAP))
        } else {
            builder.setShowWhen(false)
                .setSubText(context.getString(R.string.label_paused))
                .setContentText(StopwatchFormat.full(elapsed))
            builder.addAction(action(R.drawable.ic_play_arrow, R.string.button_resume, StopwatchReceiver.ACTION_RESUME))
            builder.addAction(action(R.drawable.ic_replay, R.string.button_reset, StopwatchReceiver.ACTION_RESET))
        }
        manager.notify(Notifications.ID_STOPWATCH, builder.build())
    }

    private fun action(icon: Int, label: Int, action: String): Notification.Action {
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StopwatchReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, icon), context.getString(label), intent).build()
    }
}

/** The notification's buttons. Not exported: only the app's own pending intents reach it. */
class StopwatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val stopwatch = (context.applicationContext as ClockApplication).stopwatch
        when (intent.action) {
            ACTION_PAUSE -> stopwatch.pause()
            ACTION_RESUME -> stopwatch.start()
            ACTION_LAP -> stopwatch.lap()
            ACTION_RESET -> stopwatch.reset()
        }
    }

    companion object {
        const val ACTION_PAUSE = "sh.hnet.clock.action.STOPWATCH_PAUSE"
        const val ACTION_RESUME = "sh.hnet.clock.action.STOPWATCH_RESUME"
        const val ACTION_LAP = "sh.hnet.clock.action.STOPWATCH_LAP"
        const val ACTION_RESET = "sh.hnet.clock.action.STOPWATCH_RESET"
    }
}

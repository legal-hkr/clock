package sh.hnet.clock.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import sh.hnet.clock.MainActivity
import sh.hnet.clock.R
import sh.hnet.clock.ui.Tab

/**
 * What the app's notifications share (SPEC §3.8): channels, ids, and the intent that opens the
 * app on a destination. Channels are silent – sounds are played by the app on the alarm stream
 * (§3.8.3). Notifications keep the system's look.
 */
object Notifications {
    const val CHANNEL_STOPWATCH = "stopwatch"
    const val ID_STOPWATCH = 1

    /** Running, paused and quietly ended timers (low); one notification each, ids from [ID_TIMER_BASE] up. */
    const val CHANNEL_TIMERS = "timers"

    /** A timer that has ended and rings (high, with the full-screen page). */
    const val CHANNEL_RINGING_TIMERS = "ringing_timers"
    const val ID_RINGING = 2

    /** An alarm ringing (high, with the full-screen page); a while before one (low); while snoozed (low); after one nobody answered (default). */
    const val CHANNEL_RINGING_ALARMS = "ringing_alarms"
    const val CHANNEL_UPCOMING_ALARMS = "upcoming_alarms"
    const val CHANNEL_SNOOZED_ALARMS = "snoozed_alarms"
    const val CHANNEL_MISSED_ALARMS = "missed_alarms"
    const val ID_TIMER_BASE = 1_000

    /** The destination a tapped notification (or widget) opens, as a [Tab] name. */
    const val EXTRA_DESTINATION = "sh.hnet.clock.extra.DESTINATION"

    fun manager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Whether the user lets the app post at all; without it the stopwatch works, only unseen (§3.11.3). */
    fun allowed(context: Context): Boolean = manager(context).areNotificationsEnabled()

    /** Creates the channels (again after a language change, so their names follow it). */
    fun ensureChannels(context: Context) {
        manager(context).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_STOPWATCH, context.getString(R.string.label_nav_stopwatch), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_TIMERS, context.getString(R.string.label_channel_timers), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                quiet(CHANNEL_UPCOMING_ALARMS, context.getString(R.string.label_channel_upcoming_alarms), NotificationManager.IMPORTANCE_LOW),
                quiet(CHANNEL_SNOOZED_ALARMS, context.getString(R.string.label_channel_snoozed_alarms), NotificationManager.IMPORTANCE_LOW),
                quiet(CHANNEL_MISSED_ALARMS, context.getString(R.string.label_channel_missed_alarms), NotificationManager.IMPORTANCE_DEFAULT),
                ringing(CHANNEL_RINGING_ALARMS, context.getString(R.string.label_channel_ringing_alarms)),
                NotificationChannel(CHANNEL_RINGING_TIMERS, context.getString(R.string.label_channel_ringing_timers), NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false)
                    // The app plays the sound itself, on the alarm stream
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
            ),
        )
    }

    private fun quiet(id: String, name: String, importance: Int) = NotificationChannel(id, name, importance).apply {
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
    }

    /** High importance for the full-screen page; silent, as the app plays the sound itself on the alarm stream. */
    private fun ringing(id: String, name: String) = quiet(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
        setBypassDnd(true)
        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
    }

    /** Opens the app on [tab]. */
    fun open(context: Context, tab: Tab): PendingIntent = PendingIntent.getActivity(
        context,
        tab.ordinal,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_DESTINATION, tab.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

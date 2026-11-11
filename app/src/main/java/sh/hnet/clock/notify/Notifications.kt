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

    /** The destination a tapped notification (or widget) opens, as a [Tab] name. */
    const val EXTRA_DESTINATION = "sh.hnet.clock.extra.DESTINATION"

    fun manager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Whether the user lets the app post at all; without it the stopwatch works, only unseen (§3.11.3). */
    fun allowed(context: Context): Boolean = manager(context).areNotificationsEnabled()

    /** Creates the channels (again after a language change, so their names follow it). */
    fun ensureChannels(context: Context) {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL_STOPWATCH, context.getString(R.string.label_nav_stopwatch), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
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

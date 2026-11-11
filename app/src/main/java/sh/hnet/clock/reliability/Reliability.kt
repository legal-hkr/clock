package sh.hnet.clock.reliability

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import sh.hnet.clock.notify.Notifications

/** What can keep an alarm or a timer from ringing (SPEC §3.2.8), in the order it is reported: one at a time, the worst first. */
enum class Problem { NOTIFICATIONS, FULL_SCREEN, DO_NOT_DISTURB, BATTERY, VOLUME }

/** Whose ringing is asked about: the two ring through channels of their own, which the user can block one by one. */
enum class Ringing { ALARMS, TIMERS, ANY }

/**
 * The phone's settings an alarm depends on, as read at one moment. Exact alarms are not among
 * them: with `USE_EXACT_ALARM` they are granted at install and can't be switched off.
 *
 * @property notifications The app may post at all
 * @property alarmChannel The channel ringing alarms use is not blocked
 * @property timerChannel The same for ended timers
 * @property fullScreen The ringing page may open over the lock screen
 * @property totalSilence Do Not Disturb is at "total silence", which mutes the alarm stream too
 * @property restricted The user set the app's battery use to "Restricted"
 */
data class Checks(
    val notifications: Boolean,
    val alarmChannel: Boolean,
    val timerChannel: Boolean,
    val fullScreen: Boolean,
    val totalSilence: Boolean,
    val restricted: Boolean,
    val volume: Int,
    val maxVolume: Int,
) {
    fun canNotify(what: Ringing): Boolean = notifications && when (what) {
        Ringing.ALARMS -> alarmChannel
        Ringing.TIMERS -> timerChannel
        Ringing.ANY -> alarmChannel && timerChannel
    }

    /** The alarm volume as a share of its range, 0–100. */
    val volumePercent: Int get() = if (maxVolume > 0) (volume * 100 + maxVolume / 2) / maxVolume else 0

    /**
     * What is wrong, in the order of [Problem]. A volume at zero counts only when a sound is
     * wanted ([soundWanted]): a silent alarm loses nothing by it.
     */
    fun problems(what: Ringing, soundWanted: Boolean): List<Problem> = buildList {
        if (!canNotify(what)) add(Problem.NOTIFICATIONS)
        if (!fullScreen) add(Problem.FULL_SCREEN)
        if (totalSilence) add(Problem.DO_NOT_DISTURB)
        if (restricted) add(Problem.BATTERY)
        if (volume <= 0 && soundWanted) add(Problem.VOLUME)
    }
}

object Reliability {
    /** Reads the settings now. Cheap: system services only, nothing from storage. */
    fun read(context: Context): Checks {
        val manager = Notifications.manager(context)
        val audio = context.getSystemService(AudioManager::class.java)
        return Checks(
            notifications = manager.areNotificationsEnabled(),
            alarmChannel = open(manager, Notifications.CHANNEL_RINGING_ALARMS),
            timerChannel = open(manager, Notifications.CHANNEL_RINGING_TIMERS),
            fullScreen = manager.canUseFullScreenIntent(),
            totalSilence = manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE,
            restricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
            volume = audio.getStreamVolume(AudioManager.STREAM_ALARM),
            maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
        )
    }

    /** A channel that doesn't exist yet is not blocked: it is created when first needed. */
    private fun open(manager: NotificationManager, channel: String): Boolean =
        manager.getNotificationChannel(channel)?.let { it.importance != NotificationManager.IMPORTANCE_NONE } ?: true

    /** Sets the alarm volume to half of its range, with the system's volume panel shown (§3.2.8 #6). */
    fun turnUp(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, (audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) + 1) / 2, AudioManager.FLAG_SHOW_UI)
        } catch (_: SecurityException) {
            // Do Not Disturb forbids the change: its own card comes before this one
        }
    }
}

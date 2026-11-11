package sh.hnet.clock.alarm

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.format.DateFormat
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.notify.Notifications
import sh.hnet.clock.ui.Tab
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The alarms' notifications other than the ringing one, which is the ringing service's (SPEC §3.8):
 * - upcoming, a while before an alarm rings, with Dismiss so it won't;
 * - snoozed, until it rings again, with Dismiss;
 * - missed, after an alarm nobody answered, until swiped away.
 * A notification's title and text are two fields: the label (or "Alarm") and what is the matter.
 */
class AlarmNotifications(private val context: Context) {

    fun show(alarms: List<Alarm>, zone: ZoneId, settings: AlarmSettings, now: Long) {
        val manager = Notifications.manager(context)
        val wanted = HashMap<Int, Notification>()
        val lead = settings.upcomingMinutes * 60_000L
        for (alarm in alarms) {
            val name = alarm.label.ifEmpty { context.getString(R.string.title_alarm) }
            alarm.snoozedUntil?.let { until ->
                wanted[SNOOZED + slot(alarm.id)] = base(Notifications.CHANNEL_SNOOZED_ALARMS, R.drawable.ic_snooze)
                    .setContentTitle(name)
                    .setContentText(context.getString(R.string.msg_snoozed_until, time(until, zone)))
                    .setOngoing(true)
                    .addAction(action(R.drawable.ic_alarm_off, R.string.button_dismiss, AlarmReceiver.ACTION_STOP, alarm.id))
                    .build()
            }
            val next = alarm.next(zone)
            if (alarm.snoozedUntil == null && alarm.ringing == null && lead > 0 && next != null && next - now <= lead) {
                wanted[UPCOMING + slot(alarm.id)] = base(Notifications.CHANNEL_UPCOMING_ALARMS, R.drawable.ic_alarm)
                    .setContentTitle(context.getString(R.string.title_alarm_at, time(next, zone)))
                    .setContentText(alarm.label.ifEmpty { null })
                    .setOngoing(true)
                    .addAction(action(R.drawable.ic_alarm_off, R.string.button_dismiss, AlarmReceiver.ACTION_DISMISS_UPCOMING, alarm.id))
                    .build()
            }
            alarm.missed?.let { missed ->
                val at = time(missed, zone)
                wanted[MISSED + slot(alarm.id)] = base(Notifications.CHANNEL_MISSED_ALARMS, R.drawable.ic_notifications_paused)
                    .setContentTitle(context.getString(R.string.title_missed_alarm))
                    .setContentText(if (alarm.label.isEmpty()) at else context.getString(R.string.label_time_and_label, at, alarm.label))
                    .setAutoCancel(true)
                    .setWhen(missed).setShowWhen(true)
                    .build()
            }
        }
        val allowed = Notifications.allowed(context)
        val active = manager.activeNotifications.map { it.id }.filter { it in UPCOMING until END }.toSet()
        // Gone: what is no longer the case. A missed one the user swiped away is not shown again
        (active - wanted.keys).forEach(manager::cancel)
        if (!allowed || wanted.isEmpty()) return
        Notifications.ensureChannels(context)
        wanted.forEach { (id, notification) ->
            val missed = id in MISSED until END
            if (!missed || id !in active && shownMissed.add(id to notification.`when`)) manager.notify(id, notification)
        }
    }

    /** Missed notifications already posted in this process, by id and moment: one swiped away stays away. */
    private val shownMissed = HashSet<Pair<Int, Long>>()

    private fun base(channel: String, icon: Int): Notification.Builder = Notification.Builder(context, channel)
        .setSmallIcon(icon)
        .setContentIntent(Notifications.open(context, Tab.ALARMS))
        .setCategory(Notification.CATEGORY_ALARM)
        .setOnlyAlertOnce(true)
        .setLocalOnly(true)
        .setShowWhen(false)

    private fun action(icon: Int, label: Int, action: String, id: Long): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, icon), context.getString(label), intent(context, action, id)).build()

    /** A time of day as the system writes it (12- or 24-hour by the system setting). */
    private fun time(moment: Long, zone: ZoneId): String = timeText(context, moment, zone)

    companion object {
        private const val UPCOMING = 2_000_000
        private const val SNOOZED = 3_000_000
        private const val MISSED = 4_000_000
        private const val END = 5_000_000

        private fun slot(alarmId: Long): Int = (alarmId % 1_000_000).toInt()

        fun timeText(context: Context, moment: Long, zone: ZoneId): String {
            val locale = context.resources.configuration.locales[0]
            val pattern = DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "Hm" else "hm")
            return DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochMilli(moment).atZone(zone))
        }

        /** A pending broadcast to [AlarmReceiver] for one alarm. */
        fun intent(context: Context, action: String, id: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            slot(id) + 10,
            Intent(context, AlarmReceiver::class.java).setAction(action).putExtra(AlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * The moments scheduled with the system and the notifications' buttons. Not exported, and aware of
 * direct boot: an alarm fires also before the phone is first unlocked after a restart.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarms = (context.applicationContext as ClockApplication).alarms
        val id = intent.getLongExtra(EXTRA_ID, 0)
        when (intent.action) {
            ACTION_FIRE, ACTION_CHECK -> alarms.refresh()
            ACTION_STOP -> alarms.stop(id)
            ACTION_SNOOZE -> alarms.snooze(id)
            ACTION_DISMISS_UPCOMING -> alarms.dismissUpcoming(id)
        }
    }

    companion object {
        const val EXTRA_ID = "sh.hnet.clock.extra.ALARM_ID"
        const val ACTION_FIRE = "sh.hnet.clock.action.ALARM_FIRE"
        const val ACTION_CHECK = "sh.hnet.clock.action.ALARM_CHECK"
        const val ACTION_STOP = "sh.hnet.clock.action.ALARM_STOP"
        const val ACTION_SNOOZE = "sh.hnet.clock.action.ALARM_SNOOZE"
        const val ACTION_DISMISS_UPCOMING = "sh.hnet.clock.action.ALARM_DISMISS_UPCOMING"
    }
}

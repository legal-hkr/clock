package sh.hnet.clock.ring

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.alarm.Alarm
import sh.hnet.clock.alarm.AlarmNotifications
import sh.hnet.clock.alarm.AlarmReceiver
import sh.hnet.clock.notify.Notifications
import sh.hnet.clock.timer.Timer
import sh.hnet.clock.timer.TimerFormat
import sh.hnet.clock.timer.TimerNotifications
import sh.hnet.clock.timer.TimerReceiver
import sh.hnet.clock.ui.Tab
import sh.hnet.clock.ui.components.durationWords
import sh.hnet.clock.ui.components.timerName
import java.time.ZoneId

/**
 * Rings while an alarm rings or a timer that ended waits for an answer: a foreground service, so
 * the sound goes on with the screen off and the app closed. Its notification is what rings (SPEC
 * §3.8): high importance with a full-screen intent – the ringing page over the lock screen, a
 * heads-up while the phone is in use – and its answers as actions. An alarm comes before timers
 * (§3.4.8): they follow, with their own sound, once it is answered.
 *
 * Aware of direct boot: before the phone is first unlocked only alarms exist for it; timers and
 * the settings store are locked until then.
 */
class RingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ringer: Ringer
    private val silence = Runnable { (application as ClockApplication).timers.silenceOverdue() }

    /** What the sound playing now belongs to: "alarm:<id>" or "timers"; null while quiet. */
    private var playingFor: String? = null

    override fun onCreate() {
        super.onCreate()
        ringer = Ringer(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as ClockApplication
        val alarm = app.alarms.ringing().firstOrNull()
        val timers = if (app.unlocked) app.timers.ringing() else emptyList()
        // Always in the foreground first: the system expects it of a service started this way
        val notification = if (alarm != null) alarmNotification(alarm) else timerNotification(timers, quiet = app.unlocked && app.timers.pageVisible)
        startForeground(Notifications.ID_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        if (alarm == null && timers.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val subject = alarm?.let { "alarm:${it.id}:${it.ringing}" } ?: "timers"
        if (subject != playingFor) {
            // Marked at once, so a second start doesn't begin another sound while settings are read
            playingFor = subject
            ringer.stop()
            if (alarm != null) {
                val sound = SoundChoice.decode(alarm.sound, alarm.soundTitle)
                ringer.start(Sounds.uris(this, sound), alarm.vibration || sound == SoundChoice.Silent, app.alarms.settings.value.gradualSeconds)
            } else scope.launch {
                val preferences = app.preferences
                val sound = preferences.timerSound.first()
                val vibrate = preferences.timerVibration.first() || sound == SoundChoice.Silent
                val gradual = preferences.timerGradual.first()
                if (playingFor == "timers") ringer.start(Sounds.uris(this@RingService, sound), vibrate, gradual)
            }
        }
        // Alarms are silenced by the alarm controller's own scheduled check; timers from here
        handler.removeCallbacks(silence)
        if (app.unlocked) {
            val now = app.timers.now()
            app.timers.nextSilence(now)?.let { at -> handler.postDelayed(silence, (at - now.sinceBoot).coerceAtLeast(0) + 50) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(silence)
        scope.cancel()
        ringer.stop()
        super.onDestroy()
    }

    private fun base(channel: String, icon: Int, tab: Tab, page: Boolean): Notification.Builder {
        Notifications.ensureChannels(this)
        val builder = Notification.Builder(this, channel)
            .setSmallIcon(icon)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(Notifications.open(this, tab))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (page) {
            val intent = PendingIntent.getActivity(
                this, 0,
                Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(intent, true)
        }
        return builder
    }

    /** A ringing alarm: its label (or "Alarm") and the time, with Snooze – when the alarm allows it – and Stop. */
    private fun alarmNotification(alarm: Alarm): Notification {
        val builder = base(Notifications.CHANNEL_RINGING_ALARMS, R.drawable.ic_alarm, Tab.ALARMS, page = true)
            .setContentTitle(alarm.label.ifEmpty { getString(R.string.title_alarm) })
            .setContentText(AlarmNotifications.timeText(this, alarm.ringing ?: System.currentTimeMillis(), ZoneId.systemDefault()))
            .setShowWhen(false)
        if (alarm.snooze) builder.addAction(action(R.drawable.ic_snooze, R.string.button_snooze, AlarmNotifications.intent(this, AlarmReceiver.ACTION_SNOOZE, alarm.id)))
        builder.addAction(action(R.drawable.ic_alarm_off, R.string.button_stop, AlarmNotifications.intent(this, AlarmReceiver.ACTION_STOP, alarm.id)))
        return builder.build()
    }

    /**
     * One ended timer: its label, the time since it ended counting up, what it was set to; Stop and
     * Add 1 minute. Several: how many, and Stop all. [quiet] while the Timer destination itself is
     * in front (§3.6.7): the same notification without the page or a heads-up.
     */
    private fun timerNotification(ringing: List<Timer>, quiet: Boolean): Notification {
        val builder = base(if (quiet) Notifications.CHANNEL_TIMERS else Notifications.CHANNEL_RINGING_TIMERS, R.drawable.ic_hourglass_empty, Tab.TIMER, page = !quiet)
        if (ringing.isEmpty()) return builder.setContentTitle(getString(R.string.label_nav_timer)).build()
        val now = (application as ClockApplication).timers.now()
        val single = ringing.singleOrNull()
        if (single != null) {
            builder.setContentTitle(timerName(resources, single))
                .setUsesChronometer(true).setWhen(single.endsAt(now)).setShowWhen(true)
                .addAction(action(R.drawable.ic_stop, R.string.button_stop, TimerNotifications.intent(this, TimerReceiver.ACTION_STOP, single.id)))
                .addAction(action(R.drawable.ic_add, R.string.button_add_minute, TimerNotifications.intent(this, TimerReceiver.ACTION_ADD_MINUTE, single.id)))
            // What it was set to; a timer without a label says that in its name already
            if (single.label.isNotEmpty()) builder.setContentText(getString(R.string.label_timer_of, durationWords(resources, single.original)))
        } else {
            builder.setContentTitle(resources.getQuantityString(R.plurals.title_timers_ended, ringing.size, ringing.size))
                .setContentText(ringing.joinToString { timerName(resources, it) + " (" + TimerFormat.left(it.remaining(now)) + ")" })
                .setShowWhen(false)
                .addAction(action(R.drawable.ic_stop, R.string.button_stop_all, TimerNotifications.intent(this, TimerReceiver.ACTION_STOP_ALL)))
        }
        return builder.build()
    }

    private fun action(icon: Int, label: Int, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), intent).build()

    companion object {
        /** Starts or stops the service to match what rings: called after every change to alarms or timers. */
        fun sync(context: Context) {
            val app = context.applicationContext as ClockApplication
            val any = app.alarms.ringing().isNotEmpty() || (app.unlocked && app.timers.ringing().isNotEmpty())
            val service = Intent(app, RingService::class.java)
            if (any) {
                try {
                    app.startForegroundService(service)
                } catch (_: RuntimeException) {
                    // The system refuses a start from the background (the process came up on its own with
                    // something long overdue): its notification is there, without the sound
                }
            } else {
                app.stopService(service)
            }
        }
    }
}

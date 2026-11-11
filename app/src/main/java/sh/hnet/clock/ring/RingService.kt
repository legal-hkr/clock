package sh.hnet.clock.ring

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
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
import sh.hnet.clock.notify.Notifications
import sh.hnet.clock.timer.Timer
import sh.hnet.clock.timer.TimerFormat
import sh.hnet.clock.timer.TimerNotifications
import sh.hnet.clock.timer.TimerReceiver
import sh.hnet.clock.ui.Tab
import sh.hnet.clock.ui.components.durationWords

/**
 * Rings while a timer that ended waits for an answer: a foreground service, so the sound goes on
 * with the screen off and the app closed. Its notification is the ended timer's (SPEC §3.8):
 * high importance with a full-screen intent – the ringing page over the lock screen, a heads-up
 * while the phone is in use – and Stop and Add 1 minute. The timer controller starts and stops it;
 * a timer nobody answers goes quiet by itself.
 */
class RingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ringer: Ringer
    private val silence = Runnable { (application as ClockApplication).timers.silenceOverdue() }

    override fun onCreate() {
        super.onCreate()
        ringer = Ringer(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as ClockApplication
        val ringing = app.timers.ringing()
        // Always in the foreground first: the system expects it of a service started this way
        startForeground(Notifications.ID_RINGING, notification(ringing, quiet = app.timers.pageVisible), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        if (ringing.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ringer.playing) {
            // Marked at once, so a second start doesn't begin another sound while the settings are read
            scope.launch {
                val preferences = app.preferences
                val sound = preferences.timerSound.first()
                val vibrate = preferences.timerVibration.first() || sound == SoundChoice.Silent
                val gradual = preferences.timerGradual.first()
                if (!ringer.playing && app.timers.ringing().isNotEmpty()) ringer.start(Sounds.uris(this@RingService, sound), vibrate, gradual)
            }
        }
        handler.removeCallbacks(silence)
        val now = app.timers.now()
        app.timers.nextSilence(now)?.let { at -> handler.postDelayed(silence, (at - now.sinceBoot).coerceAtLeast(0) + 50) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(silence)
        scope.cancel()
        ringer.stop()
        super.onDestroy()
    }

    /**
     * One ended timer: its label, the time since it ended counting up, what it was set to; Stop and
     * Add 1 minute. Several: how many, and Stop all. [quiet] while the Timer destination itself is
     * in front (§3.6.7): the same notification without the page or a heads-up.
     */
    private fun notification(ringing: List<Timer>, quiet: Boolean): Notification {
        Notifications.ensureChannels(this)
        val now = (application as ClockApplication).timers.now()
        val builder = Notification.Builder(this, if (quiet) Notifications.CHANNEL_TIMERS else Notifications.CHANNEL_RINGING_TIMERS)
            .setSmallIcon(R.drawable.ic_hourglass_empty)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(Notifications.open(this, Tab.TIMER))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (!quiet) {
            val page = PendingIntent.getActivity(
                this, 0,
                Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(page, true)
        }
        val single = ringing.singleOrNull()
        if (single != null) {
            builder.setContentTitle(single.label.ifEmpty { getString(R.string.label_timer_default) })
                .setContentText(getString(R.string.label_timer_of, durationWords(resources, single.original)))
                .setUsesChronometer(true).setWhen(single.endsAt(now)).setShowWhen(true)
                .addAction(action(R.drawable.ic_stop, R.string.button_stop, TimerNotifications.intent(this, TimerReceiver.ACTION_STOP, single.id)))
                .addAction(action(R.drawable.ic_add, R.string.button_add_minute, TimerNotifications.intent(this, TimerReceiver.ACTION_ADD_MINUTE, single.id)))
        } else {
            builder.setContentTitle(resources.getQuantityString(R.plurals.title_timers_ended, ringing.size, ringing.size))
                .setContentText(ringing.joinToString { it.label.ifEmpty { getString(R.string.label_timer_default) } + " (" + TimerFormat.left(it.remaining(now)) + ")" })
                .setShowWhen(false)
                .addAction(action(R.drawable.ic_stop, R.string.button_stop_all, TimerNotifications.intent(this, TimerReceiver.ACTION_STOP_ALL)))
        }
        return builder.build()
    }

    private fun action(icon: Int, label: Int, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), intent).build()
}

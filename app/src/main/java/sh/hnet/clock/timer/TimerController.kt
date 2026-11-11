package sh.hnet.clock.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import sh.hnet.clock.ring.RingService
import sh.hnet.clock.stopwatch.Moment
import sh.hnet.clock.stopwatch.Moments

/**
 * The timers and their presets: kept on every change, so they survive the process and a restart
 * of the phone; after each change the next end is scheduled with the system (an exact alarm that
 * wakes the phone), the notifications are brought up to date and ringing starts or stops. Used
 * from the UI, the notifications' buttons and the receivers alike: synchronous, main thread.
 */
class TimerController(private val context: Context) {
    private val store = context.getSharedPreferences("timers", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = TimerNotifications(context)
    private val _timers = MutableStateFlow(read(KEY_TIMERS, Timer.serializer()))
    private val _presets = MutableStateFlow(read(KEY_PRESETS, TimerPreset.serializer()))

    val timers: StateFlow<List<Timer>> = _timers.asStateFlow()
    val presets: StateFlow<List<TimerPreset>> = _presets.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /** Changes whenever the timers were looked at anew – also when one merely ended, which changes nothing stored. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * True while the Timer destination is in front: a timer ending then turns its card and sounds,
     * without the full-screen page or a heads-up over the very page that shows it (§3.6.7).
     */
    var pageVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            ring()
        }

    fun now(): Moment = Moments.now(context)

    /** Starts a new timer; null when there are as many as the app keeps ([MAX_TIMERS]). */
    fun start(duration: Long, label: String = ""): Timer? {
        if (_timers.value.size >= MAX_TIMERS || duration <= 0) return null
        val timer = Timer(id = nextId(), label = label, original = duration.coerceAtMost(Timer.MAX)).resume(now())
        change { it + timer }
        return timer
    }

    fun pause(id: Long) = each(id) { it.pause(now()) }
    fun resume(id: Long) = each(id) { it.resume(now()) }
    fun addMinute(id: Long) = each(id) { it.addMinute(now()) }
    fun reset(id: Long) = each(id) { it.reset() }
    fun setLabel(id: Long, label: String) = each(id) { it.copy(label = label.trim().take(MAX_LABEL)) }

    /** Removes a timer at once; what it was, for [restore] (Undo). Stop on an ended timer is this too: it is done. */
    fun delete(id: Long): Timer? {
        val timer = _timers.value.firstOrNull { it.id == id } ?: return null
        change { list -> list.filter { it.id != id } }
        return timer
    }

    /** Puts a deleted timer back as it was: a running one has kept its end meanwhile. */
    fun restore(timer: Timer) = change { list -> if (list.any { it.id == timer.id }) list else list + timer }

    /** Stops every timer that is ringing. */
    fun stopRinging() {
        val now = now()
        change { list -> list.filter { !it.ringing(now) } }
    }

    /** Timers that rang unanswered for [SILENCE_AFTER] go quiet; they stay, counting, until stopped (§3.6.7). */
    fun silenceOverdue() {
        val now = now()
        change { list -> list.map { if (it.ringing(now) && -it.remaining(now) >= SILENCE_AFTER) it.copy(silenced = true) else it } }
        // Nothing changed, but a timer may just have ended
        refresh()
    }

    /** When the next ringing timer is due to go quiet, as time since boot; null with none ringing. */
    fun nextSilence(now: Moment): Long? =
        _timers.value.filter { it.ringing(now) }.minOfOrNull { now.sinceBoot + it.remaining(now) + SILENCE_AFTER }

    fun ringing(): List<Timer> = now().let { now -> _timers.value.filter { it.ringing(now) }.shown(now) }

    // Presets

    fun addPreset(name: String, duration: Long) = presets { it + TimerPreset(nextId(), name.trim(), duration) }
    fun updatePreset(id: Long, name: String, duration: Long) =
        presets { list -> list.map { if (it.id == id) it.copy(name = name.trim(), duration = duration) else it } }

    /** Removes a preset; its position, for [insertPreset] (Undo). */
    fun removePreset(id: Long): Int {
        val position = _presets.value.indexOfFirst { it.id == id }
        if (position >= 0) presets { list -> list.filter { it.id != id } }
        return position
    }

    fun insertPreset(preset: TimerPreset, position: Int) =
        presets { list -> if (list.any { it.id == preset.id }) list else list.toMutableList().apply { add(position.coerceIn(0, size), preset) } }

    fun setPresets(list: List<TimerPreset>) = presets { list }

    /** The presets from a backup, with ids of this phone's own. */
    fun replacePresets(list: List<Pair<String, Long>>) = presets { list.map { (name, duration) -> TimerPreset(nextId(), name, duration) } }

    /**
     * Brings the system up to date with the stored timers: after the process starts, a restart of
     * the phone, an update of the app, or the scheduled end of a timer.
     */
    fun refresh() {
        val now = now()
        val list = _timers.value
        schedule(list, now)
        notifications.show(list, now)
        ring()
        _revision.value++
    }

    @Synchronized
    private fun change(transform: (List<Timer>) -> List<Timer>) {
        val next = transform(_timers.value)
        if (next == _timers.value) return
        store.edit { putString(KEY_TIMERS, json.encodeToString(ListSerializer(Timer.serializer()), next)) }
        _timers.value = next
        refresh()
    }

    private fun each(id: Long, transform: (Timer) -> Timer) = change { list -> list.map { if (it.id == id) transform(it) else it } }

    @Synchronized
    private fun presets(transform: (List<TimerPreset>) -> List<TimerPreset>) {
        val next = transform(_presets.value).take(MAX_PRESETS)
        if (next == _presets.value) return
        store.edit { putString(KEY_PRESETS, json.encodeToString(ListSerializer(TimerPreset.serializer()), next)) }
        _presets.value = next
    }

    /**
     * One exact alarm for the timer ending next; it wakes the phone and comes even in battery saving.
     * The app holds USE_EXACT_ALARM (a clock's permission, granted at install), which lint doesn't count.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun schedule(list: List<Timer>, now: Moment) {
        val intent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, TimerReceiver::class.java).setAction(TimerReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = list.filter { it.running && !it.ended(now) }.minOfOrNull { it.remaining(now) }
        if (next == null) {
            alarms.cancel(intent)
            return
        }
        val at = now.sinceBoot + next
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
        } else {
            // Without exact alarms (a system that withholds them): as close as the system allows
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
        }
    }

    /** Starts or stops the ringing service to match the timers. */
    private fun ring() {
        val any = now().let { now -> _timers.value.any { it.ringing(now) } }
        val service = Intent(context, RingService::class.java)
        if (any) {
            try {
                context.startForegroundService(service)
            } catch (_: RuntimeException) {
                // The system refuses a start from the background (the process came up on its own
                // with a timer long ended): the timer's notification is there, without the sound
            }
        } else {
            context.stopService(service)
        }
    }

    private fun nextId(): Long {
        val id = store.getLong(KEY_NEXT_ID, 1)
        store.edit { putLong(KEY_NEXT_ID, id + 1) }
        return id
    }

    private fun <T> read(key: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> = store.getString(key, null)?.let { text ->
        try {
            json.decodeFromString(ListSerializer(serializer), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }.orEmpty()

    companion object {
        const val MAX_TIMERS = 20
        const val MAX_PRESETS = 30
        const val MAX_LABEL = 60
        const val MAX_PRESET_NAME = 30

        /** Until Settings › Alarms brings the setting (stage 5): 10 minutes, its default (§3.9.1). */
        const val SILENCE_AFTER = 10 * 60_000L

        private const val KEY_TIMERS = "timers"
        private const val KEY_PRESETS = "presets"
        private const val KEY_NEXT_ID = "next_id"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

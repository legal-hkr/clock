package sh.hnet.clock.alarm

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
import sh.hnet.clock.widget.Widgets
import sh.hnet.clock.notify.Notifications
import sh.hnet.clock.ring.RingService
import sh.hnet.clock.ui.Tab
import java.time.ZoneId

/**
 * The alarms and the settings they share. Kept in device-protected storage, so an alarm can ring
 * after a restart before the phone is first unlocked (SPEC §5.8) – nothing here may touch the
 * app's other stores, which are locked until then.
 *
 * After every change, and whenever time passes a mark (the system's alarm, a restart, a change of
 * clock or time zone), [refresh] lets each alarm advance, schedules the next moment with the
 * system as an alarm clock (it wakes the phone in any battery state and shows in the status bar),
 * brings the notifications up to date and starts or stops the ringing. Synchronous, main thread.
 */
class AlarmController(context: Context) {
    private val context = context.applicationContext
    private val store = this.context.createDeviceProtectedStorageContext().getSharedPreferences("alarms", Context.MODE_PRIVATE)
    private val system = this.context.getSystemService(AlarmManager::class.java)
    private val notifications = AlarmNotifications(this.context)
    private val _alarms = MutableStateFlow(readAlarms())
    private val _settings = MutableStateFlow(readSettings())

    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()
    val settings: StateFlow<AlarmSettings> = _settings.asStateFlow()

    private fun zone(): ZoneId = ZoneId.systemDefault()
    private fun now(): Long = System.currentTimeMillis()

    /** A new alarm at this time: on, once, with the sound and vibration new alarms get (§3.2.2). Null at the limit. */
    fun add(hour: Int, minute: Int, label: String = "", days: Set<Int> = emptySet(), vibration: Boolean? = null): Alarm? {
        if (_alarms.value.size >= MAX_ALARMS) return null
        val defaults = _settings.value
        val alarm = Alarm(
            id = nextId(), hour = hour, minute = minute, days = days, label = label.trim().take(Alarm.MAX_LABEL),
            sound = defaults.sound, soundTitle = defaults.soundTitle, vibration = vibration ?: defaults.vibration,
        ).on(now(), zone())
        change { it + alarm }
        return alarm
    }

    fun setEnabled(id: Long, on: Boolean) = each(id) { if (on) it.on(now(), zone()) else it.off() }

    /** A change of what decides when it rings: an alarm that is on then looks ahead afresh. */
    fun reschedule(id: Long, transform: (Alarm) -> Alarm) = each(id) { transform(it).rescheduled(now(), zone()) }

    /** A change of anything else (label, sound, vibration, snooze). */
    fun update(id: Long, transform: (Alarm) -> Alarm) = each(id, transform)

    fun setSkipNext(id: Long, skip: Boolean) = each(id) { if (skip) it.skipNext(zone()) else it.unskip() }
    fun dismissUpcoming(id: Long) = each(id) { it.dismissUpcoming(zone()) }

    /** Stop while ringing, or Dismiss while snoozed. */
    fun stop(id: Long) = each(id) { it.stop() }

    /** Snooze, for an alarm that rings and allows it. */
    fun snooze(id: Long) = each(id) { if (it.ringing != null && it.snooze) it.snoozed(now() + _settings.value.snoozeMinutes * 60_000L) else it }

    /** Removes an alarm at once; what it was, for [restore] (Undo). */
    fun delete(id: Long): Alarm? {
        val alarm = _alarms.value.firstOrNull { it.id == id } ?: return null
        change { list -> list.filter { it.id != id } }
        return alarm
    }

    /** Puts a deleted alarm back as it was, on or off; what it missed meanwhile, it missed. */
    fun restore(alarm: Alarm) = change { list -> if (list.any { it.id == alarm.id }) list else list + alarm }

    /** The list was seen: the "missed" notes have done their work (§3.4.6). */
    fun markSeen() = change { list -> list.map { if (it.missed != null) it.copy(missed = null) else it } }

    /** The alarms ringing now, the one that began first leading. */
    fun ringing(): List<Alarm> = _alarms.value.filter { it.ringing != null }.sortedBy { it.ringing }

    /** When the ringing alarm that began first is due to stop by itself, as wall-clock time; null if never. */
    fun nextSilence(): Long? {
        val after = _settings.value.silenceAfter
        return if (after > 0) ringing().firstOrNull()?.ringing?.plus(after) else null
    }

    fun setSettings(transform: (AlarmSettings) -> AlarmSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        store.edit { putString(KEY_SETTINGS, json.encodeToString(AlarmSettings.serializer(), next)) }
        _settings.value = next
        refresh()
    }

    /** Everything from a backup, in place of what is there; ids are this phone's own, nothing rings or is snoozed. */
    fun replaceAll(alarms: List<Alarm>) {
        val now = now()
        val zone = zone()
        change { _ ->
            alarms.take(MAX_ALARMS).map { stored ->
                val clean = stored.copy(id = nextId(), since = now, skipped = null, snoozedUntil = null, ringing = null, missed = null)
                if (clean.enabled) clean.on(now, zone) else clean
            }
        }
    }

    /**
     * Lets time act on the alarms and brings the system up to date with them. Called after every
     * change and by the receivers: the scheduled moment, a restart, a change of clock or time zone.
     */
    @Synchronized
    fun refresh() {
        val now = now()
        val zone = zone()
        val settings = _settings.value
        val advanced = _alarms.value.map { it.advance(now, zone, settings.silenceAfter, settings.grace) }
        if (advanced != _alarms.value) save(advanced)
        schedule(advanced, zone, settings, now)
        notifications.show(advanced, zone, settings, now)
        RingService.sync(context)
        Widgets.digital(context)
    }

    @Synchronized
    private fun change(transform: (List<Alarm>) -> List<Alarm>) {
        val next = transform(_alarms.value)
        if (next != _alarms.value) save(next)
        refresh()
    }

    private fun each(id: Long, transform: (Alarm) -> Alarm) = change { list -> list.map { if (it.id == id) transform(it) else it } }

    private fun save(list: List<Alarm>) {
        // Written at once, not later: the process may be gone before the alarm is due
        store.edit(commit = true) { putString(KEY_ALARMS, json.encodeToString(ListSerializer(Alarm.serializer()), list)) }
        _alarms.value = list
    }

    /**
     * The next moment anything is due, as the system's alarm clock; and, earlier, a quiet wake-up to
     * show the upcoming-alarm notification, or to stop an alarm nobody answers.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun schedule(list: List<Alarm>, zone: ZoneId, settings: AlarmSettings, now: Long) {
        val fire = intent(AlarmReceiver.ACTION_FIRE, 0)
        val next = list.nextPending(zone)
        if (next == null) {
            system.cancel(fire)
        } else {
            val show = Notifications.open(context, Tab.ALARMS)
            system.setAlarmClock(AlarmManager.AlarmClockInfo(next.second, show), fire)
        }
        // The app holds USE_EXACT_ALARM (a clock's permission, granted at install), which lint doesn't count
        val check = intent(AlarmReceiver.ACTION_CHECK, 1)
        val lead = settings.upcomingMinutes * 60_000L
        val upcoming = if (lead > 0) list.mapNotNull { alarm -> alarm.takeIf { it.snoozedUntil == null }?.next(zone)?.minus(lead) }.filter { it > now }.minOrNull() else null
        val moment = listOfNotNull(upcoming, nextSilence()?.takeIf { it > now }).minOrNull()
        if (moment == null) system.cancel(check) else system.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, moment, check)
    }

    private fun intent(action: String, request: Int): PendingIntent = PendingIntent.getBroadcast(
        context, request, Intent(context, AlarmReceiver::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun nextId(): Long {
        val id = store.getLong(KEY_NEXT_ID, 1)
        store.edit(commit = true) { putLong(KEY_NEXT_ID, id + 1) }
        return id
    }

    private fun readAlarms(): List<Alarm> = decode(KEY_ALARMS) { json.decodeFromString(ListSerializer(Alarm.serializer()), it) }.orEmpty()

    private fun readSettings(): AlarmSettings = decode(KEY_SETTINGS) { json.decodeFromString(AlarmSettings.serializer(), it) } ?: AlarmSettings()

    private fun <T> decode(key: String, parse: (String) -> T): T? = store.getString(key, null)?.let { text ->
        try {
            parse(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val MAX_ALARMS = 100
        private const val KEY_ALARMS = "alarms"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_NEXT_ID = "next_id"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

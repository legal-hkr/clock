package sh.hnet.clock.alarm

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * An alarm (SPEC §3.2). It keeps its wall-clock time in whatever zone the phone is in (§5.5): every
 * ringing is worked out anew from the time of day, the days or the date, and [since].
 *
 * State lives in the alarm too, so one stored list says everything after the process or the phone
 * restarts: what is skipped, snoozed, ringing or was missed.
 *
 * @param days ISO day numbers, 1 (Monday) to 7; empty for an alarm that rings once
 * @param date ISO date of a one-off alarm with a chosen day; null = the next time this time of day comes
 * @param sound A [sh.hnet.clock.ring.SoundChoice] as stored, with [soundTitle] beside it for a sound that is gone
 * @param since The next ringing is the first one after this moment: when it was switched on or changed, or its last ringing
 * @param skipped The ringing left out by "Skip the next one"
 * @param snoozedUntil When it rings again after Snooze
 * @param ringing Since when it has been ringing
 * @param missed The ringing nobody answered, shown on the row until the list was seen
 */
@Serializable
data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val date: String? = null,
    val label: String = "",
    val sound: String = "default",
    val soundTitle: String? = null,
    val vibration: Boolean = true,
    val snooze: Boolean = true,
    val enabled: Boolean = true,
    val since: Long = 0,
    val skipped: Long? = null,
    val snoozedUntil: Long? = null,
    val ringing: Long? = null,
    val missed: Long? = null,
) {
    val repeating: Boolean get() = days.isNotEmpty()

    val time: LocalTime get() = LocalTime.of(hour, minute)

    private val day: LocalDate? get() = date?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * The first moment after [after] this alarm's time comes on one of its days, on its date, or –
     * for a one-off alarm without a date – at all (today or tomorrow, whichever comes first). A
     * time that a daylight-saving change leaves out moves on by the gap, as the clock does.
     */
    fun occurrenceAfter(after: Long, zone: ZoneId): Long? {
        val from = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        fun at(date: LocalDate) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        day?.takeIf { !repeating }?.let { fixed -> return at(fixed).takeIf { it > after } }
        // Yesterday too: after a change of time zone, yesterday's date may hold a time still ahead
        for (offset in -1L..8L) {
            val date = from.plusDays(offset)
            if (repeating && date.dayOfWeek.value !in days) continue
            at(date).takeIf { it > after }?.let { return it }
        }
        return null
    }

    /** The next regular ringing: null while off, and past the skipped one. */
    fun next(zone: ZoneId): Long? {
        if (!enabled) return null
        val first = occurrenceAfter(since, zone) ?: return null
        return if (first == skipped) occurrenceAfter(first, zone) else first
    }

    /** What the system has to wake the phone for next: the end of a snooze, else the next ringing. */
    fun pending(zone: ZoneId): Long? = if (!enabled || ringing != null) null else snoozedUntil ?: next(zone)

    /** Switched on at [now]: it looks ahead from this moment. A chosen date already past is dropped. */
    fun on(now: Long, zone: ZoneId): Alarm {
        val fresh = copy(enabled = true, since = now, skipped = null, snoozedUntil = null, ringing = null, missed = null)
        return if (!fresh.repeating && fresh.date != null && fresh.occurrenceAfter(now, zone) == null) fresh.copy(date = null) else fresh
    }

    fun off(): Alarm = copy(enabled = false, skipped = null, snoozedUntil = null, ringing = null)

    /** After its time, days or date changed: an alarm that is on looks ahead anew, with nothing skipped or snoozed. */
    fun rescheduled(now: Long, zone: ZoneId): Alarm = when {
        enabled -> on(now, zone).copy(missed = missed)
        // Off: only a date already past is dropped, so the row says "Today" or "Tomorrow" again
        !repeating && date != null && occurrenceAfter(now, zone) == null -> copy(date = null)
        else -> this
    }

    /** Skips the next ringing of a repeating alarm that is on (§3.2.5); the alarm itself stays on. */
    fun skipNext(zone: ZoneId): Alarm = if (!repeating || !enabled) this else copy(skipped = occurrenceAfter(since, zone))

    fun unskip(): Alarm = copy(skipped = null)

    /** Dismissed from the upcoming-alarm notification (§3.8): a repeating alarm skips this ringing, a one-off one switches off. */
    fun dismissUpcoming(zone: ZoneId): Alarm = if (repeating) skipNext(zone) else off()

    /** Stop while ringing, or Dismiss while snoozed (§3.4.5): this ringing is over. A one-off alarm switches off. */
    fun stop(): Alarm = copy(ringing = null, snoozedUntil = null).let { if (repeating) it else it.copy(enabled = false) }

    /** Snooze while ringing: quiet until [until]. Without limit (§3.4.5). */
    fun snoozed(until: Long): Alarm = copy(ringing = null, snoozedUntil = until)

    /**
     * What the passing of time does to the alarm, at [now]:
     * - a skipped ringing whose time has passed is behind it;
     * - ringing unanswered for [silenceAfter] (0 = never), it stops and counts as missed (§3.4.6);
     * - its pending moment reached, it starts to ring – unless that moment is more than [grace]
     *   behind (the phone was off, or the clock was set forward a long way): then it was missed.
     *   A moment only just jumped over rings at once (§5.5).
     * A missed one-off alarm switches off; a repeating one stays on.
     *
     * A clock set back (by hand, or corrected by the network) leaves the alarm's marks ahead of the
     * present; they are brought back to it first. Otherwise an alarm set while the clock ran ahead
     * would pass over its next ringing, and a snooze or a ringing could last as long as the jump.
     */
    fun advance(now: Long, zone: ZoneId, silenceAfter: Long, grace: Long): Alarm {
        var alarm = this
        if (alarm.since > now) alarm = alarm.copy(since = now)
        alarm.ringing?.let { started -> if (started > now) alarm = alarm.copy(ringing = now) }
        alarm.snoozedUntil?.let { until -> if (until - now > MAX_SNOOZE) alarm = alarm.copy(snoozedUntil = now + MAX_SNOOZE) }
        alarm.skipped?.let { skip -> if (skip <= now) alarm = alarm.copy(since = maxOf(alarm.since, skip), skipped = null) }
        alarm.ringing?.let { started ->
            if (silenceAfter > 0 && now - started >= silenceAfter) alarm = alarm.missedAt(started) else return alarm
        }
        // Several ringings may lie behind after days with the phone off: each is passed in turn
        repeat(MAX_CATCH_UP) {
            val due = alarm.pending(zone)?.takeIf { it <= now } ?: return alarm
            val regular = alarm.snoozedUntil == null
            alarm = if (now - due > grace) {
                alarm.missedAt(due).let { if (regular) it.copy(since = maxOf(it.since, due)) else it }
            } else {
                return alarm.copy(ringing = due.coerceAtMost(now), snoozedUntil = null, since = if (regular) maxOf(alarm.since, due) else alarm.since)
            }
        }
        return alarm
    }

    private fun missedAt(moment: Long): Alarm = copy(ringing = null, snoozedUntil = null, missed = moment).let { if (repeating) it else it.copy(enabled = false) }

    companion object {
        private const val MAX_CATCH_UP = 16
        const val MAX_LABEL = 60

        /** The longest a snooze can be set to (the last of [AlarmSettings.SNOOZE_CHOICES]). */
        private const val MAX_SNOOZE = 30 * 60_000L
    }
}

/** What all alarms share (SPEC §3.9.1). Kept with the alarms, where it can be read before the phone is first unlocked. */
@Serializable
data class AlarmSettings(
    val snoozeMinutes: Int = 10,
    /** 0 = never. */
    val silenceAfterMinutes: Int = 10,
    val gradualSeconds: Int = 0,
    val volumeButtons: VolumeButtons = VolumeButtons.SNOOZE,
    /** For new alarms. */
    val sound: String = "default",
    val soundTitle: String? = null,
    val vibration: Boolean = true,
    /** Minutes before an alarm its upcoming notification shows; 0 = off. */
    val upcomingMinutes: Int = 60,
) {
    val silenceAfter: Long get() = silenceAfterMinutes * 60_000L

    /** How far behind a ringing may be and still ring at once: as long as it would have rung anyway. */
    val grace: Long get() = if (silenceAfterMinutes > 0) silenceAfter else 30 * 60_000L

    companion object {
        val SNOOZE_CHOICES = listOf(1, 3, 5, 10, 15, 20, 30)
        val SILENCE_CHOICES = listOf(1, 5, 10, 15, 20, 30, 0)
        val UPCOMING_CHOICES = listOf(0, 30, 60, 120)
    }
}

/** What the volume buttons do while an alarm rings (§3.4.9). */
enum class VolumeButtons { SNOOZE, STOP, NOTHING }

/** The alarm whose pending moment comes first, with that moment; null with none on. */
fun List<Alarm>.nextPending(zone: ZoneId): Pair<Alarm, Long>? =
    mapNotNull { alarm -> alarm.pending(zone)?.let { alarm to it } }.minByOrNull { it.second }

/** The list's order (§3.2.1): by time of day, then as they were made. */
fun List<Alarm>.shown(): List<Alarm> = sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))

/**
 * "Rings in 7 h 12 min", "Rings in 2 days 3 h" (§3.2.1): the two largest units, minutes rounded up
 * so it never promises less than is left. All zero = less than a minute.
 */
data class RingsIn(val days: Int, val hours: Int, val minutes: Int) {
    val lessThanMinute: Boolean get() = days == 0 && hours == 0 && minutes == 0

    companion object {
        fun of(ms: Long): RingsIn {
            if (ms < 60_000) return RingsIn(0, 0, 0)
            val minutes = (ms + 59_999) / 60_000
            val days = (minutes / 1440).toInt()
            val hours = (minutes % 1440 / 60).toInt()
            return if (days > 0) RingsIn(days, hours, 0) else RingsIn(0, hours, (minutes % 60).toInt())
        }
    }
}

/** How a repeating alarm's days read (§3.2.1). */
enum class DaysKind { EVERY_DAY, WEEKDAYS, WEEKENDS, CUSTOM }

object Days {
    /** [weekend] is the locale's (Saturday and Sunday in most places, Friday and Saturday in some). */
    fun kind(days: Set<Int>, weekend: Set<Int>): DaysKind = when {
        days.size == 7 -> DaysKind.EVERY_DAY
        weekend.isNotEmpty() && days == weekend -> DaysKind.WEEKENDS
        weekend.isNotEmpty() && days == (1..7).toSet() - weekend -> DaysKind.WEEKDAYS
        else -> DaysKind.CUSTOM
    }

    /** The seven days in week order, starting on [first]. */
    fun week(first: DayOfWeek): List<DayOfWeek> = List(7) { first.plus(it.toLong()) }
}

package sh.hnet.clock.ui.alarms

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import sh.hnet.clock.R
import sh.hnet.clock.alarm.Alarm
import sh.hnet.clock.alarm.Days
import sh.hnet.clock.alarm.DaysKind
import sh.hnet.clock.alarm.RingsIn
import sh.hnet.clock.ui.components.TimeFormats
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** "Rings in 7 h 12 min", "Rings in 2 days 3 h", "Rings in less than a minute" (SPEC §3.2.1). */
fun ringsInText(resources: Resources, ms: Long): String {
    val left = RingsIn.of(ms)
    if (left.lessThanMinute) return resources.getString(R.string.label_rings_in_less_than_minute)
    val parts = listOfNotNull(
        if (left.days > 0) resources.getQuantityString(R.plurals.label_duration_days, left.days, left.days) else null,
        if (left.hours > 0) resources.getString(R.string.label_duration_h, left.hours) else null,
        if (left.minutes > 0) resources.getString(R.string.label_duration_min, left.minutes) else null,
    )
    return resources.getString(R.string.label_rings_in, parts.joinToString(" "))
}

@Composable
fun ringsInText(ms: Long): String = ringsInText(LocalResources.current, ms)

/** "Today", "Tomorrow", or a date ("Fri 20 Nov") for the day of [moment] as seen at [now]. */
fun dayText(resources: Resources, formats: TimeFormats, moment: Long, now: Instant, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(moment)
    return when (ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), at.atZone(zone).toLocalDate())) {
        0L -> resources.getString(R.string.label_today)
        1L -> resources.getString(R.string.label_tomorrow)
        else -> formats.shortDate(at, zone, now)
    }
}

/**
 * When an alarm rings, in a few words (§3.2.1): "Every day", "Weekdays", "Weekends", else its days'
 * short names in week order ("Mon, Wed, Fri"); for a one-off alarm "Today", "Tomorrow" or its date.
 */
fun daysText(resources: Resources, formats: TimeFormats, alarm: Alarm, now: Instant, zone: ZoneId): String {
    if (!alarm.repeating) {
        // Off, it says when it would ring if switched on now
        val moment = alarm.next(zone) ?: alarm.occurrenceAfter(now.toEpochMilli(), zone) ?: return resources.getString(R.string.label_once)
        return dayText(resources, formats, moment, now, zone)
    }
    return when (Days.kind(alarm.days, formats.weekend)) {
        DaysKind.EVERY_DAY -> resources.getString(R.string.label_every_day)
        DaysKind.WEEKDAYS -> resources.getString(R.string.label_weekdays)
        DaysKind.WEEKENDS -> resources.getString(R.string.label_weekends)
        DaysKind.CUSTOM -> Days.week(formats.firstDay).filter { it.value in alarm.days }.joinToString(", ") { formats.dayShort(it) }
    }
}

/** A ringing's day and time for a chip or a row: "Today, 14:00", "Tomorrow, 06:30", "Thu, 06:30" within a week, else "Fri 20 Nov, 14:00". */
fun whenText(resources: Resources, formats: TimeFormats, moment: Long, now: Instant, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(moment)
    val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), at.atZone(zone).toLocalDate())
    val day = when {
        days == 0L -> resources.getString(R.string.label_today)
        days == 1L -> resources.getString(R.string.label_tomorrow)
        days in 2..6 -> formats.weekdayShort(at, zone)
        else -> formats.shortDate(at, zone, now)
    }
    return resources.getString(R.string.label_day_and_time, day, formats.time(at, zone))
}

/** A ringing's date and time in full, for the row that names the one skipped: "Thu 12 Nov, 06:30". */
fun dateTimeText(resources: Resources, formats: TimeFormats, moment: Long, now: Instant, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(moment)
    return resources.getString(R.string.label_day_and_time, formats.shortDate(at, zone, now), formats.time(at, zone))
}

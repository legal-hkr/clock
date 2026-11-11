package sh.hnet.clock.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Tabular figures, so a running time doesn't shift as its digits change (SPEC §2.5). */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/**
 * The current moment, renewed at every full minute (or second) while the screen is at least
 * started, and at once when the phone's time or time zone is changed.
 */
@Composable
fun rememberNow(everySecond: Boolean): State<Instant> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var changes by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                changes++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return produceState(Instant.now(), everySecond, changes, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = Instant.now()
                val unit = if (everySecond) 1_000L else 60_000L
                delay(unit - System.currentTimeMillis() % unit)
            }
        }
    }
}

/**
 * Times and dates as the system writes them: 12- or 24-hour by the system setting, patterns by the
 * app's language. The app has no format settings of its own (SPEC §1.2).
 *
 * A time comes in parts, so the hero time can set them in different sizes (§3.5.1, §5.6):
 * [Parts.digits] "23:18" or "6:30", [Parts.seconds] ":42", [Parts.period] "AM" (null on a
 * 24-hour clock) and whether the period is written before the digits (as in Korean).
 */
class TimeFormats(private val locale: Locale, is24Hour: Boolean) {
    class Parts(val digits: String, val seconds: String, val period: String?, val periodFirst: Boolean)

    private val full = DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hms" else "hms")
    private val periodFirst = full.trimStart().startsWith("a")
    private val hasPeriod = full.contains('a')

    // "h:mm:ss a" without its period marker and the spaces around it
    private val bare = full.replace(PERIOD, "").trim()
    private val minutesEnd = bare.indexOf("mm").let { if (it < 0) bare.length else it + 2 }
    private val digits = DateTimeFormatter.ofPattern(bare.substring(0, minutesEnd), locale)
    private val seconds = DateTimeFormatter.ofPattern(bare.substring(minutesEnd).ifEmpty { ":ss" }, locale)
    private val period = DateTimeFormatter.ofPattern("a", locale)
    private val time = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm"), locale)
    private val date = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", locale)
    private val weekdayShort = DateTimeFormatter.ofPattern("EEE", locale)
    private val shortDate = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
    private val shortDateYear = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMMy"), locale)

    fun parts(at: Instant, zone: ZoneId): Parts {
        val local = at.atZone(zone)
        return Parts(digits.format(local), seconds.format(local), if (hasPeriod) period.format(local) else null, periodFirst)
    }

    /** "23:18", "11:18 PM". */
    fun time(at: Instant, zone: ZoneId): String = time.format(at.atZone(zone))

    /** "Wednesday, 11 November"; starts with a capital letter, as a line of its own. */
    fun date(at: Instant, zone: ZoneId): String = date.format(at.atZone(zone)).replaceFirstChar { it.titlecase(locale) }

    /** "Friday", for a date further away than tomorrow or yesterday. */
    fun weekday(at: Instant, zone: ZoneId): String = weekday.format(at.atZone(zone)).replaceFirstChar { it.titlecase(locale) }

    /** "Thu", standing alone. */
    fun weekdayShort(at: Instant, zone: ZoneId): String = weekdayShort.format(at.atZone(zone)).replaceFirstChar { it.titlecase(locale) }

    /** "Fri 20 Nov", with the year when it isn't [now]'s ("Fri 20 Nov 2027"). */
    fun shortDate(at: Instant, zone: ZoneId, now: Instant): String {
        val local = at.atZone(zone)
        val text = (if (local.year == now.atZone(zone).year) shortDate else shortDateYear).format(local)
        return text.replaceFirstChar { it.titlecase(locale) }
    }

    /** The days of the week by their shortest and short names, standing alone ("M", "Mon"). */
    fun dayNarrow(day: java.time.DayOfWeek): String = day.getDisplayName(java.time.format.TextStyle.NARROW_STANDALONE, locale)
    fun dayShort(day: java.time.DayOfWeek): String = day.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
    fun dayFull(day: java.time.DayOfWeek): String = day.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }

    /** The week as the system's regional settings have it: its first day and its weekend, as ISO day numbers. */
    val firstDay: java.time.DayOfWeek = when (androidx.core.text.util.LocalePreferences.getFirstDayOfWeek(locale)) {
        androidx.core.text.util.LocalePreferences.FirstDayOfWeek.SUNDAY -> java.time.DayOfWeek.SUNDAY
        androidx.core.text.util.LocalePreferences.FirstDayOfWeek.SATURDAY -> java.time.DayOfWeek.SATURDAY
        androidx.core.text.util.LocalePreferences.FirstDayOfWeek.FRIDAY -> java.time.DayOfWeek.FRIDAY
        androidx.core.text.util.LocalePreferences.FirstDayOfWeek.THURSDAY -> java.time.DayOfWeek.THURSDAY
        androidx.core.text.util.LocalePreferences.FirstDayOfWeek.WEDNESDAY -> java.time.DayOfWeek.WEDNESDAY
        androidx.core.text.util.LocalePreferences.FirstDayOfWeek.TUESDAY -> java.time.DayOfWeek.TUESDAY
        else -> java.time.DayOfWeek.MONDAY
    }
    val weekend: Set<Int> = run {
        val data = android.icu.util.Calendar.getInstance(android.icu.util.ULocale.forLocale(locale)).weekData
        // ICU counts Sunday as 1; ISO counts Monday as 1
        fun iso(day: Int) = (day + 5) % 7 + 1
        generateSequence(iso(data.weekendOnset)) { it % 7 + 1 }.takeWhile { it != iso(data.weekendCease) }.toSet() + iso(data.weekendCease)
    }

    private companion object {
        /** The period marker with the (possibly non-breaking or narrow) spaces around it. */
        val PERIOD = Regex("[\\s\\u00A0\\u202F]*a+[\\s\\u00A0\\u202F]*")
    }
}

@Composable
fun rememberTimeFormats(): TimeFormats {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    // Read again whenever the screen is composed anew: the 12/24-hour setting changes outside the app
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(locale, is24Hour) { TimeFormats(locale, is24Hour) }
}

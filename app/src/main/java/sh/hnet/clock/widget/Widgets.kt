package sh.hnet.clock.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.MainActivity
import sh.hnet.clock.R
import sh.hnet.clock.alarm.nextPending
import sh.hnet.clock.notify.Notifications
import sh.hnet.clock.reliability.Reliability
import sh.hnet.clock.reliability.Ringing
import sh.hnet.clock.ring.SoundChoice
import sh.hnet.clock.stopwatch.Moment
import sh.hnet.clock.time.TimeDifference
import sh.hnet.clock.time.zoneOrNull
import sh.hnet.clock.timer.Timer
import sh.hnet.clock.timer.TimerFormat
import sh.hnet.clock.timer.TimerNotifications
import sh.hnet.clock.timer.TimerPreset
import sh.hnet.clock.timer.TimerReceiver
import sh.hnet.clock.timer.shown
import sh.hnet.clock.ui.Tab
import sh.hnet.clock.ui.components.TimeFormats
import sh.hnet.clock.ui.components.presetTitle
import sh.hnet.clock.ui.components.timerName
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The home-screen widgets (SPEC §3.10): Digital clock, Analogue clock, World clock, Timer. Plain
 * system widgets – the launcher draws them from layouts and the system's dynamic colors, and the
 * system's own views keep them right without the app running: the text clock ticks, the analogue
 * clock moves its hands, the chronometer counts a timer down. The app only redraws a widget when
 * what it shows changes: an alarm, the cities, a timer, the presets, the language, the clock.
 *
 * Every widget carries a layout for each size it can take; the launcher picks the one that fits,
 * so resizing needs no word from the app and nothing is ever cut (§8 stage 7).
 */
object Widgets {
    /** Opens the Timer destination with the New timer sheet (the widget's "+"). */
    const val EXTRA_NEW_TIMER = "sh.hnet.clock.extra.NEW_TIMER"

    private const val WIDE = 230f
    private const val NARROW = 50f
    /** The least widths (dp) at which a timer's line holds two buttons, and at which presets stand two to a line and the one-line widget has "+" too. */
    private const val MIDDLE = 250f
    private const val TIMER_WIDE = 310f

    /** A line of the timer widget with the space under it (dp), and the least heights of a widget of one to five lines (12 dp around). */
    private const val LINE = 52f
    private val TIMER_HEIGHTS = listOf(40f, 124f, 176f, 228f, 280f)
    /** The least width (dp) at which the digital clock's group has its full size. */
    private const val DIGITAL_FULL = 320f

    /** The sides (dp) the round clock is laid out for; a widget may carry at most 16 layouts. */
    private val ROUND_SIDES = listOf(110f, 125f, 140f, 155f, 170f, 185f, 200f, 220f, 240f, 260f, 285f, 310f, 340f, 370f, 400f, 440f)

    /** The round clock's digits and its date and alarm text, as shares of the cookie's side: 48 dp and 12 dp on a cookie of about 155 dp. */
    private const val ROUND_DIGITS = 0.31f
    private const val ROUND_TEXT = 0.075f
    private const val REQUEST_NEW_TIMER = 100
    private const val REQUEST_LIST = 101
    private const val REQUEST_LIST_TIMERS = 102

    /** The period marker of a time pattern with the (possibly non-breaking or narrow) spaces around it. */
    private val PERIOD = Regex("[\\s\\u00A0\\u202F]*a+[\\s\\u00A0\\u202F]*")

    /** Everything again: after a restart, an unlock, a change of language, clock or time zone. */
    fun all(context: Context) {
        digital(context)
        round(context)
        analog(context)
        world(context)
        timer(context)
    }

    private fun ids(context: Context, widget: Class<*>): IntArray = try {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, widget))
    } catch (_: RuntimeException) {
        // No widget service (a phone without a home screen for widgets)
        IntArray(0)
    }

    private fun show(context: Context, widget: Class<*>, views: RemoteViews) =
        AppWidgetManager.getInstance(context).updateAppWidget(ComponentName(context, widget), views)

    /** One layout for each size a widget can take: [build] is asked for every pair of the given least widths and heights (dp). */
    private fun sized(widths: List<Float>, heights: List<Float>, build: (width: Float, height: Float) -> RemoteViews): RemoteViews =
        RemoteViews(buildMap { for (width in widths) for (height in heights) put(SizeF(width, height), build(width, height)) })

    private fun locale(context: Context): Locale = context.resources.configuration.locales[0]

    /** The locale's own pattern for the hour and minute; in 12-hour time with or without the period ("6:30 AM", "6:30"). */
    private fun timePatterns(locale: Locale, period: Boolean): Pair<String, String> {
        val twelve = DateFormat.getBestDateTimePattern(locale, "hm")
        return (if (period) twelve else twelve.replace(PERIOD, "")) to DateFormat.getBestDateTimePattern(locale, "Hm")
    }

    private fun RemoteViews.clockFormat(view: Int, twelve: String, twentyFour: String = twelve) {
        setCharSequence(view, "setFormat12Hour", twelve)
        setCharSequence(view, "setFormat24Hour", twentyFour)
    }

    // ---- Digital clock and Round digital clock (§3.10.1, digital widgets handoff of 12 Nov 2026) ----

    /** The next alarm as the widgets show it, and whether alarms may not ring (§3.2.8): the text stays the alarm's time either way. */
    private class AlarmLine(val text: String, val problem: Boolean) {
        fun description(context: Context): String = if (problem) context.getString(R.string.widget_alarm_may_not_ring, text) else text
    }

    private fun alarmLine(context: Context, locale: Locale): AlarmLine? {
        val alarms = (context.applicationContext as ClockApplication).alarms.alarms.value
        val zone = ZoneId.systemDefault()
        val next = alarms.nextPending(zone) ?: return null
        val problem = Reliability.read(context).problems(Ringing.ALARMS, alarms.any { it.enabled && SoundChoice.decode(it.sound, it.soundTitle) != SoundChoice.Silent }).isNotEmpty()
        return AlarmLine(alarmText(context, locale, next.second, zone), problem)
    }

    /** The hours of the locale's time pattern up to the minutes ("HH:", "h:", "H."): the widgets' clocks show hours and minutes apart and never the period. */
    private fun hourPatterns(locale: Locale, separator: Boolean): Pair<String, String> {
        val (twelve, twentyFour) = timePatterns(locale, period = false)
        fun hours(pattern: String) = pattern.substringBefore('m').let { if (separator) it else it.filter { letter -> letter.isLetter() } }
        return hours(twelve) to hours(twentyFour)
    }

    /** A card with the date in the cookie, the time with the minutes in primary, and the next alarm as a pill. One size, 4 × 2; in narrow cells the group steps down once. */
    fun digital(context: Context) {
        if (ids(context, DigitalClockWidget::class.java).isEmpty()) return
        val locale = locale(context)
        val line = alarmLine(context, locale)
        val (twelve, twentyFour) = hourPatterns(locale, separator = true)
        val views = sized(listOf(250f, DIGITAL_FULL), listOf(110f)) { width, _ ->
            RemoteViews(context.packageName, R.layout.widget_digital).apply {
                clockFormat(R.id.hours, twelve, twentyFour)
                clockFormat(R.id.weekday, DateFormat.getBestDateTimePattern(locale, "EEE"))
                clockFormat(R.id.day, DateFormat.getBestDateTimePattern(locale, "d"))
                clockFormat(R.id.month, DateFormat.getBestDateTimePattern(locale, "MMM"))
                if (width < DIGITAL_FULL) {
                    setViewLayoutWidth(R.id.cookie, 104f, TypedValue.COMPLEX_UNIT_DIP)
                    setViewLayoutHeight(R.id.cookie, 104f, TypedValue.COMPLEX_UNIT_DIP)
                    setTextViewTextSize(R.id.day, TypedValue.COMPLEX_UNIT_DIP, 44f)
                    setTextViewTextSize(R.id.hours, TypedValue.COMPLEX_UNIT_DIP, 48f)
                    setTextViewTextSize(R.id.minutes, TypedValue.COMPLEX_UNIT_DIP, 48f)
                }
                if (line != null) {
                    setViewVisibility(R.id.alarm, View.VISIBLE)
                    setTextViewText(R.id.alarm_text, line.text)
                    setContentDescription(R.id.alarm, line.description(context))
                    setImageViewResource(R.id.alarm_icon, if (line.problem) R.drawable.ic_error else R.drawable.ic_alarm)
                    setInt(R.id.alarm_pill, "setBackgroundResource", if (line.problem) R.drawable.widget_pill_error else R.drawable.widget_pill)
                    val color = if (line.problem) R.color.widget_on_error_container else R.color.widget_on_secondary_container
                    setColorStateList(R.id.alarm_icon, "setImageTintList", if (line.problem) R.color.widget_error else color)
                    setColorStateList(R.id.alarm_text, "setTextColor", color)
                    setOnClickPendingIntent(R.id.alarm, Notifications.open(context, Tab.ALARMS))
                } else {
                    setViewVisibility(R.id.alarm, View.GONE)
                }
                setOnClickPendingIntent(android.R.id.background, Notifications.open(context, Tab.CLOCK))
            }
        }
        show(context, DigitalClockWidget::class.java, views)
    }

    /**
     * The cookie with the hours over the minutes, the short date above and the next alarm below.
     * Everything in it follows the cookie's size: a widget can't scale its text by itself, so there
     * is one layout for each of [ROUND_SIDES] and the launcher shows the largest that fits.
     */
    fun round(context: Context) {
        if (ids(context, RoundClockWidget::class.java).isEmpty()) return
        val locale = locale(context)
        val line = alarmLine(context, locale)
        val (twelve, twentyFour) = hourPatterns(locale, separator = false)
        val dip = TypedValue.COMPLEX_UNIT_DIP
        // Square sizes only: the cookie is as large as the smaller side
        val views = RemoteViews(ROUND_SIDES.associate { side ->
            SizeF(side, side) to RemoteViews(context.packageName, R.layout.widget_digital_round).apply {
                clockFormat(R.id.hours, twelve, twentyFour)
                clockFormat(R.id.date, DateFormat.getBestDateTimePattern(locale, "EEEdMMM"))
                val digits = side * ROUND_DIGITS
                // The date and the alarm grow with the cookie but never go under label-medium
                val text = (side * ROUND_TEXT).coerceAtLeast(12f)
                for (view in intArrayOf(R.id.hours, R.id.minutes)) {
                    setTextViewTextSize(view, dip, digits)
                    // A line keeps empty room above and below its digits: without it hours and minutes nearly touch
                    setViewLayoutMargin(view, RemoteViews.MARGIN_TOP, -0.19f * digits, dip)
                    setViewLayoutMargin(view, RemoteViews.MARGIN_BOTTOM, -0.23f * digits, dip)
                }
                setTextViewTextSize(R.id.date, dip, text)
                setTextViewTextSize(R.id.alarm_text, dip, text)
                setViewLayoutWidth(R.id.alarm_icon, text * 1.5f, dip)
                setViewLayoutHeight(R.id.alarm_icon, text * 1.5f, dip)
                if (line != null) {
                    setViewVisibility(R.id.alarm, View.VISIBLE)
                    setTextViewText(R.id.alarm_text, line.text)
                    setContentDescription(R.id.alarm, line.description(context))
                    setImageViewResource(R.id.alarm_icon, if (line.problem) R.drawable.ic_error else R.drawable.ic_alarm)
                    val color = if (line.problem) R.color.widget_error else R.color.widget_on_primary_container
                    setColorStateList(R.id.alarm_icon, "setImageTintList", color)
                    setColorStateList(R.id.alarm_text, "setTextColor", color)
                } else {
                    setViewVisibility(R.id.alarm, View.GONE)
                }
                // While alarms may not ring, a tap leads to where that is said and fixed
                setOnClickPendingIntent(android.R.id.background, Notifications.open(context, if (line?.problem == true) Tab.ALARMS else Tab.CLOCK))
            }
        })
        show(context, RoundClockWidget::class.java, views)
    }

    /** "Thu, 06:30" within a week, else the date. No "today" or "tomorrow": the line must stay true past midnight without a redraw. */
    private fun alarmText(context: Context, locale: Locale, moment: Long, zone: ZoneId): String {
        val formats = TimeFormats(locale, DateFormat.is24HourFormat(context))
        val now = Instant.now()
        val at = Instant.ofEpochMilli(moment)
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), at.atZone(zone).toLocalDate())
        val day = if (days in 0..6) formats.weekdayShort(at, zone) else formats.shortDate(at, zone, now)
        return context.getString(R.string.label_day_and_time, day, formats.time(at, zone))
    }

    // ---- Analogue clock (§3.10.2) ----

    fun analog(context: Context) {
        if (ids(context, AnalogClockWidget::class.java).isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.widget_analog)
        views.setOnClickPendingIntent(android.R.id.background, Notifications.open(context, Tab.CLOCK))
        show(context, AnalogClockWidget::class.java, views)
    }

    // ---- World clock (§3.10.3) ----

    private class Place(val zoneId: String, val name: String, val dayShift: Int)

    /**
     * The user's cities in the app's order. Read from storage off the main thread; [done] runs
     * when the widget is drawn (a broadcast receiver waits for it).
     */
    fun world(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext as ClockApplication
        // The cities are locked until the phone is first unlocked after a restart
        if (ids(context, WorldClockWidget::class.java).isEmpty() || !app.unlocked) {
            done?.invoke()
            return
        }
        app.appScope.launch {
            try {
                val locale = locale(context)
                val names = app.zoneNames(locale)
                val local = ZoneId.systemDefault()
                val now = Instant.now()
                val zones = app.cities.current().mapNotNull { id -> zoneOrNull(id)?.let { id to it } }
                val places = zones.map { (id, zone) -> Place(id, names.city(id, now).name, TimeDifference.between(local, zone, now).dayShift) }
                withContext(Dispatchers.Main) {
                    show(context, WorldClockWidget::class.java, worldViews(context, locale, places))
                    scheduleDayChange(context, zones.map { it.second } + local, now)
                }
            } finally {
                done?.invoke()
            }
        }
    }

    private fun worldViews(context: Context, locale: Locale, places: List<Place>): RemoteViews {
        val open = Notifications.open(context, Tab.CLOCK)
        if (places.isEmpty()) {
            return RemoteViews(context.packageName, R.layout.widget_empty).apply {
                setImageViewResource(R.id.empty_icon, R.drawable.ic_schedule)
                setTextViewText(R.id.empty_text, context.getString(R.string.widget_world_empty))
                setOnClickPendingIntent(android.R.id.background, open)
            }
        }
        return sized(listOf(NARROW, WIDE), listOf(40f, 120f)) { width, height ->
            val wide = width >= WIDE
            if (height < 120f) {
                // One row: the first city, the second beside it where there is room
                RemoteViews(context.packageName, R.layout.widget_world_row).apply {
                    val (twelve, twentyFour) = timePatterns(locale, period = false)
                    listOf(Triple(R.id.place1, R.id.city1, R.id.time1), Triple(R.id.place2, R.id.city2, R.id.time2)).forEachIndexed { index, (place, city, time) ->
                        val shown = places.getOrNull(index)?.takeIf { index == 0 || wide }
                        setViewVisibility(place, if (shown != null) View.VISIBLE else View.GONE)
                        shown ?: return@forEachIndexed
                        setTextViewText(city, shown.name)
                        setString(time, "setTimeZone", shown.zoneId)
                        clockFormat(time, twelve, twentyFour)
                    }
                    setOnClickPendingIntent(android.R.id.background, open)
                }
            } else {
                RemoteViews(context.packageName, R.layout.widget_world_list).apply {
                    val (twelve, twentyFour) = timePatterns(locale, period = true)
                    val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                    places.forEachIndexed { index, place ->
                        items.addItem(index.toLong(), RemoteViews(context.packageName, R.layout.widget_world_item).apply {
                            setTextViewText(R.id.city, place.name)
                            setString(R.id.time, "setTimeZone", place.zoneId)
                            clockFormat(R.id.time, twelve, twentyFour)
                            val day = when {
                                place.dayShift > 0 -> R.string.label_tomorrow
                                place.dayShift < 0 -> R.string.label_yesterday
                                else -> null
                            }
                            setViewVisibility(R.id.day, if (day != null) View.VISIBLE else View.GONE)
                            day?.let { setTextViewText(R.id.day, context.getString(it)) }
                            setOnClickFillInIntent(R.id.row, Intent())
                        })
                    }
                    setRemoteAdapter(R.id.list, items.build())
                    // A list's rows share one intent the system fills in for each: it has to be changeable
                    setPendingIntentTemplate(
                        R.id.list,
                        PendingIntent.getActivity(
                            context, REQUEST_LIST,
                            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(Notifications.EXTRA_DESTINATION, Tab.CLOCK.name),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        ),
                    )
                    setOnClickPendingIntent(android.R.id.background, open)
                }
            }
        }
    }

    /**
     * "Tomorrow" and "Yesterday" change when a day begins – here or in one of the cities. One
     * redraw is asked for at the next such midnight. It need not wake the phone: nobody is looking.
     */
    private fun scheduleDayChange(context: Context, zones: List<ZoneId>, now: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, WorldClockWidget::class.java).setAction(WorldClockWidget.ACTION_DAY_CHANGE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = zones.takeIf { it.size > 1 }?.minOfOrNull { zone -> now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
        if (next == null) alarms.cancel(intent) else alarms.set(AlarmManager.RTC, next + 1_000, intent)
    }

    // ---- Timer (§3.10.4, widget handoff of 12 Nov 2026) ----

    fun timer(context: Context) {
        val app = context.applicationContext as ClockApplication
        // Timers are locked until the phone is first unlocked after a restart
        if (ids(context, TimerWidget::class.java).isEmpty() || !app.unlocked) return
        val now = app.timers.now()
        val timers = app.timers.timers.value.shown(now)
        val presets = app.timers.presets.value
        val open = Notifications.open(context, Tab.TIMER)
        val add = newTimer(context)
        val title = context.getString(R.string.label_nav_timer)
        // Where a timer would end silently (notifications off…), a preset opens Timer, with its problem card, instead of starting one
        val canRing = Reliability.read(context).problems(Ringing.TIMERS, soundWanted = false).isEmpty()
        // Lines stay 48 dp: at a large font the name gives way to the time
        val names = context.resources.configuration.fontScale < 1.15f
        val views = sized(listOf(NARROW, MIDDLE, TIMER_WIDE), TIMER_HEIGHTS) { width, height ->
            val lines = TIMER_HEIGHTS.indexOf(height) + 1
            // A timer's line has room for one button two cells wide, for both from three
            val buttons = if (width >= MIDDLE) 2 else 1
            val perLine = if (width >= TIMER_WIDE) 2 else 1
            when {
                lines == 1 && timers.isEmpty() -> RemoteViews(context.packageName, R.layout.widget_timer_idle_row).apply {
                    // Two cells wide, or without presets: the widget's icon and name stand where the presets would
                    val shown = if (width >= MIDDLE) presets.take(perLine) else emptyList()
                    setTextViewText(R.id.title, title)
                    removeAllViews(R.id.pills1)
                    shown.forEach { addView(R.id.pills1, pill(context, it, canRing, open)) }
                    setViewVisibility(R.id.pills1, if (shown.isEmpty()) View.GONE else View.VISIBLE)
                    setViewVisibility(R.id.icon, if (shown.isEmpty()) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.title, if (shown.isEmpty()) View.VISIBLE else View.GONE)
                    setContentDescription(R.id.add, context.getString(R.string.button_new_timer))
                    setOnClickPendingIntent(R.id.add, add)
                    setOnClickPendingIntent(android.R.id.background, open)
                }
                // One line: the first timer and, four cells wide, "+"
                lines == 1 -> RemoteViews(context.packageName, R.layout.widget_timer_row).apply {
                    val plus = width >= TIMER_WIDE
                    removeAllViews(R.id.rows)
                    addView(R.id.rows, timerLine(context, timers.first(), now, buttons, big = true, names = true, endPadding = if (plus) 8 else 12, open = open))
                    setViewVisibility(R.id.add, if (plus) View.VISIBLE else View.GONE)
                    setContentDescription(R.id.add, context.getString(R.string.button_new_timer))
                    setOnClickPendingIntent(R.id.add, add)
                    setOnClickPendingIntent(android.R.id.background, open)
                }
                else -> RemoteViews(context.packageName, R.layout.widget_timer_large).apply {
                    // With no timer the first line is the widget's name with "+"; otherwise the timers take lines from the top, and the presets keep the last line and every line the timers don't need
                    setViewVisibility(R.id.header, if (timers.isEmpty()) View.VISIBLE else View.GONE)
                    setTextViewText(R.id.title, title)
                    setContentDescription(R.id.add, context.getString(R.string.button_new_timer))
                    setOnClickPendingIntent(R.id.add, add)
                    val timerLines = timers.size.coerceAtMost(lines - 1)
                    removeAllViews(R.id.rows)
                    if (timers.size > timerLines) {
                        // More timers than lines: they scroll, the presets under them don't
                        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                        timers.forEach { items.addItem(it.id, timerLine(context, it, now, buttons, big = false, names = names, endPadding = 12, open = null)) }
                        setRemoteAdapter(R.id.list, items.build())
                        // Rows of a list can't carry intents of their own: one template, and each button fills in its action and timer
                        setPendingIntentTemplate(
                            R.id.list,
                            PendingIntent.getBroadcast(context, REQUEST_LIST_TIMERS, Intent(context, TimerReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE),
                        )
                        setViewLayoutHeight(R.id.list, timerLines * LINE, TypedValue.COMPLEX_UNIT_DIP)
                        setViewVisibility(R.id.list, View.VISIBLE)
                    } else {
                        setViewVisibility(R.id.list, View.GONE)
                        timers.forEach { addView(R.id.rows, timerLine(context, it, now, buttons, big = false, names = names, endPadding = 12, open = open)) }
                    }
                    val presetLines = lines - if (timers.isEmpty()) 1 else timerLines
                    val groups = presets.chunked(perLine).take(presetLines).ifEmpty { if (timers.isEmpty()) emptyList() else listOf(emptyList()) }
                    removeAllViews(R.id.presets)
                    groups.forEachIndexed { index, group ->
                        // While timers stand where the header was, "+" ends the last line of presets
                        val plus = if (timers.isNotEmpty() && index == groups.lastIndex) add else null
                        addView(R.id.presets, pills(context, group, perLine, canRing, open, plus))
                    }
                    setOnClickPendingIntent(android.R.id.background, open)
                }
            }
        }
        show(context, TimerWidget::class.java, views)
    }

    /** A line of presets; one that isn't full keeps the pills' width with empty places. [add], if given, ends the line as "+". */
    private fun pills(context: Context, presets: List<TimerPreset>, perLine: Int, canRing: Boolean, open: PendingIntent, add: PendingIntent?): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_timer_pills).apply {
            removeAllViews(R.id.pills)
            presets.forEach { addView(R.id.pills, pill(context, it, canRing, open)) }
            repeat(perLine - presets.size) {
                addView(R.id.pills, RemoteViews(context.packageName, R.layout.widget_timer_pill).apply { setViewVisibility(R.id.pill, View.INVISIBLE) })
            }
            if (add != null) {
                addView(R.id.pills, RemoteViews(context.packageName, R.layout.widget_timer_add).apply {
                    setContentDescription(R.id.add, context.getString(R.string.button_new_timer))
                    setOnClickPendingIntent(R.id.add, add)
                })
            }
        }

    /** A preset as a pill, "Tea (3 min)" or "9 min": a tap starts one more timer without opening the app. */
    private fun pill(context: Context, preset: TimerPreset, canRing: Boolean, open: PendingIntent): RemoteViews = RemoteViews(context.packageName, R.layout.widget_timer_pill).apply {
        setTextViewText(R.id.pill, presetTitle(context.resources, preset))
        setOnClickPendingIntent(R.id.pill, if (canRing) TimerNotifications.intent(context, TimerReceiver.ACTION_START_PRESET, preset.id) else open)
    }

    /**
     * A timer's line: name over time, then – with room for two [buttons] – "+1:00" while it runs or
     * has ended, Delete while it is paused, and always Pause, Resume or Stop. Delete exists only on
     * a paused timer: a home screen has no Undo, so nothing removes a running timer in one tap.
     *
     * @param open Opens Timer from the name and time; null in a list, whose rows fill in the list's template instead
     */
    private fun timerLine(context: Context, timer: Timer, now: Moment, buttons: Int, big: Boolean, names: Boolean, endPadding: Int, open: PendingIntent?): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_timer_item).apply {
            val density = context.resources.displayMetrics.density
            fun px(dp: Int) = (dp * density).toInt()
            fun click(view: Int, action: String) {
                if (open != null) setOnClickPendingIntent(view, TimerNotifications.intent(context, action, timer.id))
                else setOnClickFillInIntent(view, Intent().setAction(action).putExtra(TimerReceiver.EXTRA_ID, timer.id))
            }
            val ended = timer.ended(now)
            val paused = !timer.running
            setViewPadding(R.id.item, px(20), px(2), px(endPadding), px(2))
            setTextViewText(R.id.label, timerName(context.resources, timer))
            setViewVisibility(R.id.label, if (names) View.VISIBLE else View.GONE)
            // The widget of one line has the height for a larger time
            val size = if (big) 28f else 22f
            setTextViewTextSize(R.id.left, TypedValue.COMPLEX_UNIT_SP, size)
            setTextViewTextSize(R.id.left_paused, TypedValue.COMPLEX_UNIT_SP, size)
            if (timer.running) {
                // The system counts by itself from here: down to the end, and up from it once ended ("−0:12")
                setViewVisibility(R.id.left, View.VISIBLE)
                setViewVisibility(R.id.left_paused, View.GONE)
                setChronometerCountDown(R.id.left, !ended)
                setChronometer(R.id.left, SystemClock.elapsedRealtime() + timer.remaining(now), if (ended) "−%s" else null, true)
            } else {
                // Paused: the time left stands, as text – a stopped chronometer would be read anew whenever the launcher redraws
                setViewVisibility(R.id.left, View.GONE)
                setViewVisibility(R.id.left_paused, View.VISIBLE)
                setTextViewText(R.id.left_paused, TimerFormat.left(timer.remaining(now)))
            }
            setViewVisibility(R.id.plus, if (buttons > 1 && !paused) View.VISIBLE else View.GONE)
            setTextViewText(R.id.plus, context.getString(R.string.label_plus_minute))
            setContentDescription(R.id.plus, context.getString(R.string.button_add_minute))
            click(R.id.plus, TimerReceiver.ACTION_ADD_MINUTE)
            setViewVisibility(R.id.delete, if (buttons > 1 && paused) View.VISIBLE else View.GONE)
            setContentDescription(R.id.delete, context.getString(R.string.button_delete_timer))
            click(R.id.delete, TimerReceiver.ACTION_STOP)
            val (icon, label, action) = when {
                ended -> Triple(R.drawable.ic_stop, R.string.button_stop, TimerReceiver.ACTION_STOP)
                timer.running -> Triple(R.drawable.ic_pause, R.string.button_pause, TimerReceiver.ACTION_PAUSE)
                else -> Triple(R.drawable.ic_play_arrow, R.string.button_resume, TimerReceiver.ACTION_RESUME)
            }
            setImageViewResource(R.id.action, icon)
            // Round while it runs, a rounded square to resume, as in the app
            setInt(R.id.action, "setBackgroundResource", if (paused) R.drawable.widget_square_primary else R.drawable.widget_circle_primary)
            setContentDescription(R.id.action, context.getString(label))
            click(R.id.action, action)
            if (open != null) setOnClickPendingIntent(R.id.open, open)
        }

    private fun newTimer(context: Context): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_NEW_TIMER,
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(Notifications.EXTRA_DESTINATION, Tab.TIMER.name)
            .putExtra(EXTRA_NEW_TIMER, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Digital clock: time, date and the next alarm. */
class DigitalClockWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.digital(context)
}

/** Analogue clock: the face alone. */
class RoundClockWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.round(context)
}

class AnalogClockWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.analog(context)
}

/** World clock: the user's cities. */
class WorldClockWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DAY_CHANGE) redraw(context) else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    /** The cities come from storage: the broadcast is held until the widget is drawn. */
    private fun redraw(context: Context) {
        val pending = goAsync()
        Widgets.world(context) { pending.finish() }
    }

    companion object {
        const val ACTION_DAY_CHANGE = "sh.hnet.clock.action.WIDGET_DAY_CHANGE"
    }
}

/** Timer: presets to start with a tap; running timers with their controls. */
class TimerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.timer(context)
}

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
import android.text.format.DateUtils
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
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
import sh.hnet.clock.timer.TimerNotifications
import sh.hnet.clock.timer.TimerPreset
import sh.hnet.clock.timer.TimerReceiver
import sh.hnet.clock.timer.shown
import sh.hnet.clock.ui.Tab
import sh.hnet.clock.ui.components.TimeFormats
import sh.hnet.clock.ui.components.durationWords
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
    /** The widths (dp) the timer widget is laid out for – the presets that fit are reckoned for each –, the least height that holds a timer's name, time and presets, and the room "+" takes. */
    private val TIMER_WIDTHS = listOf(250f, 290f, 330f, 370f, 410f)
    private const val TIMER_TALL = 130f
    private const val ADD_WIDTH = 36f

    /** The least width (dp) at which the world clock without a background shows two cities side by side. */
    private const val BARE_WORLD_TWO = 300f

    /** The sides (dp) the round clock is laid out for; a widget may carry at most 16 layouts. */
    private val ROUND_SIDES = listOf(110f, 125f, 140f, 155f, 170f, 185f, 200f, 220f, 240f, 260f, 285f, 310f, 340f, 370f, 400f, 440f)

    /** The round clock's digits and its date and alarm text, as shares of the cookie's side: 48 dp and 12 dp on a cookie of about 155 dp. */
    private const val ROUND_DIGITS = 0.31f
    private const val ROUND_TEXT = 0.075f
    private const val REQUEST_NEW_TIMER = 100
    private const val REQUEST_LIST = 101

    /** The hour's letters in a time pattern. */
    private val HOUR = Regex("[HhKk]+")

    /** The period marker of a time pattern with the (possibly non-breaking or narrow) spaces around it. */
    private val PERIOD = Regex("[\\s\\u00A0\\u202F]*a+[\\s\\u00A0\\u202F]*")

    /** Everything again: after a restart, an unlock, a change of language, clock or time zone. */
    fun all(context: Context) {
        clocks(context)
        analog(context)
        bareAnalog(context)
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

    // ---- The clock widgets (§3.10.1, §3.10.2, lighter widgets handoff of 13 Nov 2026) ----

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

    /**
     * A time pattern without the period, taken apart for clocks that show hours and minutes in
     * views of their own: the hour's letters ("HH", "h") and what follows them (":mm").
     */
    private class TimeParts(pattern: String) {
        // Only the hour's own letters count: a pattern may carry quoted words ("HH 'h' mm")
        private val match = HOUR.find(pattern)
        val hours: String = match?.value ?: "H"
        val rest: String = match?.let { pattern.substring(it.range.last + 1) } ?: ":mm"
    }

    /** The alarm line of a widget, or nothing where no alarm is on; [tint] colors its icon and text unless that is left to the layout. */
    private fun RemoteViews.alarm(context: Context, line: AlarmLine?, tint: Int? = null) {
        if (line == null) {
            setViewVisibility(R.id.alarm, View.GONE)
            return
        }
        setViewVisibility(R.id.alarm, View.VISIBLE)
        setTextViewText(R.id.alarm_text, line.text)
        setContentDescription(R.id.alarm, line.description(context))
        // Told by the icon as well, never by color alone
        setImageViewResource(R.id.alarm_icon, if (line.problem) R.drawable.ic_error else R.drawable.ic_alarm)
        if (tint != null) {
            setColorStateList(R.id.alarm_icon, "setImageTintList", tint)
            setColorStateList(R.id.alarm_text, "setTextColor", tint)
        }
    }

    /** The widgets that show the next alarm: again when it changes, or when what decides whether it rings does. */
    fun clocks(context: Context) {
        digital(context)
        round(context)
        bareDigital(context)
        bareSmall(context)
    }

    /** No card: the date in the light cookie, over it a pill with the time and the day's name, under that a small pill with the next alarm – the arrangement of Music's player widget. */
    fun digital(context: Context) {
        if (ids(context, DigitalClockWidget::class.java).isEmpty()) return
        val locale = locale(context)
        val line = alarmLine(context, locale)
        val (twelve, twentyFour) = timePatterns(locale, period = false)
        val clock = Notifications.open(context, Tab.CLOCK)
        val views = RemoteViews(context.packageName, R.layout.widget_digital).apply {
            clockFormat(R.id.time, twelve, twentyFour)
            clockFormat(R.id.weekday, DateFormat.getBestDateTimePattern(locale, "EEE"))
            clockFormat(R.id.day, DateFormat.getBestDateTimePattern(locale, "d"))
            clockFormat(R.id.month, DateFormat.getBestDateTimePattern(locale, "MMM"))
            clockFormat(R.id.day_name, DateFormat.getBestDateTimePattern(locale, "EEEE"))
            // The pill is the widget's one accent; where alarms may not ring it turns to the error role, with the error icon
            alarm(context, line, tint = if (line?.problem == true) R.color.widget_on_error else R.color.widget_on_primary)
            setInt(R.id.alarm_pill, "setBackgroundResource", if (line?.problem == true) R.drawable.widget_float_error else R.drawable.widget_float_primary)
            setOnClickPendingIntent(R.id.alarm, Notifications.open(context, Tab.ALARMS))
            setOnClickPendingIntent(R.id.cookie, clock)
            setOnClickPendingIntent(R.id.time_pill, clock)
        }
        show(context, DigitalClockWidget::class.java, views)
    }

    /**
     * The cookie with the hours over the minutes, the short date above and the next alarm below.
     * Everything in it follows the cookie's size: a widget can't scale its text by itself, so there
     * is one layout for each of [ROUND_SIDES] and the launcher shows the largest that fits. Each
     * placed widget has its own cookie, solid or see-through.
     */
    fun round(context: Context) {
        val ids = ids(context, RoundClockWidget::class.java)
        if (ids.isEmpty()) return
        val locale = locale(context)
        val line = alarmLine(context, locale)
        val (twelve, twentyFour) = timePatterns(locale, period = false).let { TimeParts(it.first).hours to TimeParts(it.second).hours }
        val dip = TypedValue.COMPLEX_UNIT_DIP
        val manager = AppWidgetManager.getInstance(context)
        for (id in ids) {
            val alpha = cookieAlpha(context, id)
            // Square sizes only: the cookie is as large as the smaller side
            manager.updateAppWidget(id, RemoteViews(ROUND_SIDES.associate { side ->
                SizeF(side, side) to RemoteViews(context.packageName, R.layout.widget_digital_round).apply {
                    setInt(R.id.cookie, "setImageAlpha", alpha)
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
                    alarm(context, line, tint = if (line?.problem == true) R.color.widget_error else R.color.widget_on_secondary_container)
                    // While alarms may not ring, a tap leads to where that is said and fixed
                    setOnClickPendingIntent(android.R.id.background, Notifications.open(context, if (line?.problem == true) Tab.ALARMS else Tab.CLOCK))
                }
            }))
        }
    }

    /** The cookie's opacity for one placed widget: 55 % where its see-through setting is on (§6.3). What stands on the cookie stays solid. */
    private fun cookieAlpha(context: Context, id: Int): Int = if (WidgetSettings.seeThrough(context, id)) 140 else 255

    // ---- Analog clock (§3.10.2) ----

    fun analog(context: Context) {
        val ids = ids(context, AnalogClockWidget::class.java)
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        for (id in ids) {
            manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_analog).apply {
                setInt(R.id.cookie, "setImageAlpha", cookieAlpha(context, id))
                setOnClickPendingIntent(android.R.id.background, Notifications.open(context, Tab.CLOCK))
            })
        }
    }

    // ---- The widgets without a background (lighter widgets handoff §7): white with a soft shadow, straight on the wallpaper ----

    fun bareDigital(context: Context) {
        if (ids(context, BareDigitalWidget::class.java).isEmpty()) return
        val locale = locale(context)
        val (twelve, twentyFour) = timePatterns(locale, period = false).let { TimeParts(it.first) to TimeParts(it.second) }
        val clock = Notifications.open(context, Tab.CLOCK)
        val views = RemoteViews(context.packageName, R.layout.widget_bare_digital).apply {
            // Hours medium, the separator and the minutes light
            clockFormat(R.id.hours, twelve.hours, twentyFour.hours)
            clockFormat(R.id.minutes, twelve.rest, twentyFour.rest)
            clockFormat(R.id.date, DateFormat.getBestDateTimePattern(locale, "EEEdMMM"))
            alarm(context, alarmLine(context, locale))
            setOnClickPendingIntent(R.id.alarm, Notifications.open(context, Tab.ALARMS))
            setOnClickPendingIntent(R.id.date, clock)
            setOnClickPendingIntent(R.id.time, clock)
        }
        show(context, BareDigitalWidget::class.java, views)
    }

    fun bareSmall(context: Context) {
        if (ids(context, BareSmallWidget::class.java).isEmpty()) return
        val locale = locale(context)
        val line = alarmLine(context, locale)
        val (twelve, twentyFour) = timePatterns(locale, period = false).let { TimeParts(it.first).hours to TimeParts(it.second).hours }
        val views = RemoteViews(context.packageName, R.layout.widget_bare_small).apply {
            clockFormat(R.id.hours, twelve, twentyFour)
            clockFormat(R.id.date, DateFormat.getBestDateTimePattern(locale, "EEEdMMM"))
            alarm(context, line)
            // While alarms may not ring, a tap leads to where that is said and fixed
            setOnClickPendingIntent(android.R.id.background, Notifications.open(context, if (line?.problem == true) Tab.ALARMS else Tab.CLOCK))
        }
        show(context, BareSmallWidget::class.java, views)
    }

    fun bareAnalog(context: Context) {
        if (ids(context, BareAnalogWidget::class.java).isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.widget_bare_analog)
        views.setOnClickPendingIntent(android.R.id.background, Notifications.open(context, Tab.CLOCK))
        show(context, BareAnalogWidget::class.java, views)
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

    // ---- World clock (§3.10.3, further widgets handoff of 13 Nov 2026): as floating pills, and without a background ----

    private class Place(val zoneId: String, val name: String, val dayShift: Int)

    /**
     * The user's cities in the app's order, on both world clock widgets. Read from storage off the
     * main thread; [done] runs when the widgets are drawn (a broadcast receiver waits for it).
     */
    fun world(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext as ClockApplication
        val pills = ids(context, WorldClockWidget::class.java).isNotEmpty()
        val bare = ids(context, BareWorldWidget::class.java).isNotEmpty()
        // The cities are locked until the phone is first unlocked after a restart
        if ((!pills && !bare) || !app.unlocked) {
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
                    if (pills) show(context, WorldClockWidget::class.java, worldViews(context, locale, places))
                    if (bare) show(context, BareWorldWidget::class.java, bareWorldViews(context, locale, places))
                    scheduleDayChange(context, zones.map { it.second } + local, now)
                }
            } finally {
                done?.invoke()
            }
        }
    }

    /** A list's rows share one intent the system fills in for each: it has to be changeable. Here it opens Clock. */
    private fun RemoteViews.rowsOpenClock(context: Context) = setPendingIntentTemplate(
        R.id.list,
        PendingIntent.getActivity(
            context, REQUEST_LIST,
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Notifications.EXTRA_DESTINATION, Tab.CLOCK.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ),
    )

    /**
     * A city's pill. [tight] in the one-row sizes: less padding, a smaller time and the day as
     * "+1" or "−1" instead of the word.
     */
    private fun worldPill(context: Context, place: Place, twelve: String, twentyFour: String, tight: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_world_item).apply {
            setTextViewText(R.id.city, place.name)
            setString(R.id.time, "setTimeZone", place.zoneId)
            clockFormat(R.id.time, twelve, twentyFour)
            val day = when {
                place.dayShift > 0 -> if (tight) R.string.widget_world_day_ahead else R.string.label_tomorrow
                place.dayShift < 0 -> if (tight) R.string.widget_world_day_behind else R.string.label_yesterday
                else -> null
            }
            setViewVisibility(R.id.day, if (day != null) View.VISIBLE else View.GONE)
            day?.let { setTextViewText(R.id.day, context.getString(it)) }
            if (tight) {
                val side = (16 * context.resources.displayMetrics.density).toInt()
                setViewPadding(R.id.pill, side, 0, side, 0)
                setTextViewTextSize(R.id.time, TypedValue.COMPLEX_UNIT_DIP, 16f)
            }
        }

    private fun worldViews(context: Context, locale: Locale, places: List<Place>): RemoteViews {
        val open = Notifications.open(context, Tab.CLOCK)
        if (places.isEmpty()) {
            // One pill that says where cities come from
            return RemoteViews(context.packageName, R.layout.widget_world_row).apply {
                removeAllViews(R.id.place1)
                addView(R.id.place1, RemoteViews(context.packageName, R.layout.widget_world_item).apply {
                    setTextViewText(R.id.city, context.getString(R.string.widget_world_empty))
                    setColorStateList(R.id.city, "setTextColor", R.color.widget_on_surface_variant)
                    setViewVisibility(R.id.time, View.GONE)
                })
                setViewVisibility(R.id.place2, View.GONE)
                setOnClickPendingIntent(android.R.id.background, open)
            }
        }
        return sized(listOf(NARROW, WIDE), listOf(40f, 120f)) { width, height ->
            val wide = width >= WIDE
            if (height < 120f) {
                // One row: the first city, the second beside it where there is room
                RemoteViews(context.packageName, R.layout.widget_world_row).apply {
                    val (twelve, twentyFour) = timePatterns(locale, period = false)
                    listOf(R.id.place1, R.id.place2).forEachIndexed { index, slot ->
                        val shown = places.getOrNull(index)?.takeIf { index == 0 || wide }
                        removeAllViews(slot)
                        setViewVisibility(slot, if (shown != null) View.VISIBLE else View.GONE)
                        if (shown != null) addView(slot, worldPill(context, shown, twelve, twentyFour, tight = true))
                    }
                    setOnClickPendingIntent(android.R.id.background, open)
                }
            } else {
                RemoteViews(context.packageName, R.layout.widget_world_list).apply {
                    val (twelve, twentyFour) = timePatterns(locale, period = true)
                    val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                    places.forEachIndexed { index, place ->
                        items.addItem(index.toLong(), worldPill(context, place, twelve, twentyFour, tight = false).apply { setOnClickFillInIntent(R.id.row, Intent()) })
                    }
                    setRemoteAdapter(R.id.list, items.build())
                    rowsOpenClock(context)
                    setOnClickPendingIntent(android.R.id.background, open)
                }
            }
        }
    }

    /** The world clock without a background: white with a soft shadow, the times in a light weight. */
    private fun bareWorldViews(context: Context, locale: Locale, places: List<Place>): RemoteViews {
        val open = Notifications.open(context, Tab.CLOCK)
        if (places.isEmpty()) {
            return RemoteViews(context.packageName, R.layout.widget_bare_world_row).apply {
                setViewVisibility(R.id.places, View.GONE)
                setViewVisibility(R.id.empty_text, View.VISIBLE)
                setTextViewText(R.id.empty_text, context.getString(R.string.widget_world_empty))
                setOnClickPendingIntent(android.R.id.background, open)
            }
        }
        return sized(listOf(NARROW, WIDE), listOf(40f, 120f)) { width, height ->
            if (height < 120f) {
                // One row: cities side by side, as many as fit at their fixed width
                RemoteViews(context.packageName, R.layout.widget_bare_world_row).apply {
                    val (twelve, twentyFour) = timePatterns(locale, period = false)
                    setViewVisibility(R.id.places, View.VISIBLE)
                    setViewVisibility(R.id.empty_text, View.GONE)
                    removeAllViews(R.id.places)
                    places.take(if (width >= BARE_WORLD_TWO) 2 else 1).forEach { place ->
                        addView(R.id.places, RemoteViews(context.packageName, R.layout.widget_bare_world_place).apply {
                            // The day's word follows the name here: there is no line of its own for it
                            setTextViewText(R.id.city, when {
                                place.dayShift > 0 -> context.getString(R.string.widget_world_tomorrow, place.name)
                                place.dayShift < 0 -> context.getString(R.string.widget_world_yesterday, place.name)
                                else -> place.name
                            })
                            setString(R.id.time, "setTimeZone", place.zoneId)
                            clockFormat(R.id.time, twelve, twentyFour)
                        })
                    }
                    setOnClickPendingIntent(android.R.id.background, open)
                }
            } else {
                RemoteViews(context.packageName, R.layout.widget_bare_world_list).apply {
                    val (twelve, twentyFour) = timePatterns(locale, period = false)
                    val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                    places.forEachIndexed { index, place ->
                        items.addItem(index.toLong(), RemoteViews(context.packageName, R.layout.widget_bare_world_item).apply {
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
                    rowsOpenClock(context)
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

    // ---- Timer (§3.10.4; behavior: widget handoff of 12 Nov 2026, layout: further widgets handoff of 13 Nov 2026 §5) ----

    /**
     * No card and no lines: one timer – the first in the app's order – on the wallpaper in white,
     * with the two buttons of its state and one row of presets; the other timers are only counted.
     * With no timer, the widget's name and up to two rows of presets. One row high: the time and
     * buttons alone, or the presets alone.
     */
    fun timer(context: Context) {
        val app = context.applicationContext as ClockApplication
        // Timers are locked until the phone is first unlocked after a restart
        if (ids(context, TimerWidget::class.java).isEmpty() || !app.unlocked) return
        val now = app.timers.now()
        val timers = app.timers.timers.value.shown(now)
        val presets = app.timers.presets.value
        val open = Notifications.open(context, Tab.TIMER)
        val add = newTimer(context)
        // Where a timer would end silently (notifications off…), a preset opens Timer, with its problem card, instead of starting one
        val canRing = Reliability.read(context).problems(Ringing.TIMERS, soundWanted = false).isEmpty()
        val timer = timers.firstOrNull()
        val views = sized(TIMER_WIDTHS, listOf(40f, TIMER_TALL)) { width, height ->
            val tall = height >= TIMER_TALL
            RemoteViews(context.packageName, R.layout.widget_timer).apply {
                setViewVisibility(R.id.header, if (timer == null && tall) View.VISIBLE else View.GONE)
                setTextViewText(R.id.title, context.getString(R.string.label_nav_timer))
                setViewVisibility(R.id.name_row, if (timer != null && tall) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.time_row, if (timer != null) View.VISIBLE else View.GONE)
                if (timer != null) {
                    setTextViewText(R.id.label, timerName(context.resources, timer))
                    val others = timers.size - 1
                    setViewVisibility(R.id.more, if (others > 0) View.VISIBLE else View.GONE)
                    setTextViewText(R.id.more, context.getString(R.string.widget_timer_more, others))
                    timerRow(context, timer, now, large = tall)
                    setOnClickPendingIntent(R.id.name_row, open)
                    setOnClickPendingIntent(R.id.open, open)
                }
                // Presets: one row under a timer, up to two without one, none beside a timer in the one-row widget
                val rows = when {
                    timer == null && tall -> 2
                    timer == null || tall -> 1
                    else -> 0
                }
                val lines = presetLines(context, presets, width - 16f, rows)
                listOf(R.id.presets1, R.id.presets2).forEachIndexed { index, container ->
                    removeAllViews(container)
                    val line = lines.getOrNull(index)
                    setViewVisibility(container, if (line != null) View.VISIBLE else View.GONE)
                    line ?: return@forEachIndexed
                    line.forEach { preset ->
                        addView(container, RemoteViews(context.packageName, R.layout.widget_timer_pill).apply {
                            // A named preset shows its name only; one without a name, its duration
                            setTextViewText(R.id.pill_text, presetName(context, preset))
                            setOnClickPendingIntent(R.id.pill, if (canRing) TimerNotifications.intent(context, TimerReceiver.ACTION_START_PRESET, preset.id) else open)
                        })
                    }
                    // "+" ends the last row
                    if (index == lines.lastIndex) {
                        addView(container, RemoteViews(context.packageName, R.layout.widget_timer_add).apply {
                            setContentDescription(R.id.add, context.getString(R.string.button_new_timer))
                            setOnClickPendingIntent(R.id.add, add)
                        })
                    }
                }
                setOnClickPendingIntent(android.R.id.background, open)
            }
        }
        show(context, TimerWidget::class.java, views)
    }

    private fun presetName(context: Context, preset: TimerPreset): String = preset.name.ifEmpty { durationWords(context.resources, preset.duration) }

    /**
     * The presets that fit [rows] rows of [width] dp, in their order, with room left for "+" at the
     * end of the last one. A widget can't measure its text, so a pill's width is reckoned from the
     * length of its name; rather a preset too few than one cut off. Never empty while [rows] is
     * at least one: "+" needs a row even without presets.
     */
    private fun presetLines(context: Context, presets: List<TimerPreset>, width: Float, rows: Int): List<List<TimerPreset>> {
        if (rows < 1) return emptyList()
        val lines = mutableListOf(mutableListOf<TimerPreset>())
        var used = 0f
        for (preset in presets) {
            // 14 dp medium text is about 8 dp a letter; 24 dp of padding, 6 dp to the next pill; a pill is 160 dp of text at most
            val pill = (presetName(context, preset).length * 8f).coerceAtMost(160f) + 30f
            if (used + pill > width - ADD_WIDTH) {
                if (lines.size == rows) break
                lines += mutableListOf<TimerPreset>()
                used = 0f
            }
            lines.last() += preset
            used += pill
        }
        return lines
    }

    /**
     * The shown timer's time and its two buttons, by its state: "+1:00" and Pause while it runs,
     * Delete and Resume while it is paused, "+1:00" and Stop once it has ended. Delete exists only
     * on a paused timer: a home screen has no Undo, so nothing removes a running timer in one tap.
     */
    private fun RemoteViews.timerRow(context: Context, timer: Timer, now: Moment, large: Boolean) {
        fun click(view: Int, action: String) = setOnClickPendingIntent(view, TimerNotifications.intent(context, action, timer.id))
        val ended = timer.ended(now)
        val paused = !timer.running
        // The widget of one row has less height for the time
        val size = if (large) 64f else 44f
        setTextViewTextSize(R.id.left, TypedValue.COMPLEX_UNIT_DIP, size)
        setTextViewTextSize(R.id.left_paused, TypedValue.COMPLEX_UNIT_DIP, size)
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
            // Written as the system's chronometer writes it ("03:59"), so the time doesn't change its form when the timer is paused; rounded up to the second, as everywhere
            setTextViewText(R.id.left_paused, DateUtils.formatElapsedTime((timer.remaining(now) + 999) / 1000))
        }
        setViewVisibility(R.id.plus, if (!paused) View.VISIBLE else View.GONE)
        setTextViewText(R.id.plus, context.getString(R.string.label_plus_minute))
        setContentDescription(R.id.plus, context.getString(R.string.button_add_minute))
        click(R.id.plus, TimerReceiver.ACTION_ADD_MINUTE)
        setViewVisibility(R.id.delete, if (paused) View.VISIBLE else View.GONE)
        setContentDescription(R.id.delete, context.getString(R.string.button_delete_timer))
        click(R.id.delete, TimerReceiver.ACTION_STOP)
        // Pause and Resume are a rounded square, Stop is round
        setViewVisibility(R.id.action, if (!ended) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.stop, if (ended) View.VISIBLE else View.GONE)
        setImageViewResource(R.id.action, if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause)
        setContentDescription(R.id.action, context.getString(if (paused) R.string.button_resume else R.string.button_pause))
        click(R.id.action, if (paused) TimerReceiver.ACTION_RESUME else TimerReceiver.ACTION_PAUSE)
        setContentDescription(R.id.stop, context.getString(R.string.button_stop))
        click(R.id.stop, TimerReceiver.ACTION_STOP)
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
    override fun onDeleted(context: Context, ids: IntArray) = WidgetSettings.forget(context, ids)
}

class AnalogClockWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.analog(context)
    override fun onDeleted(context: Context, ids: IntArray) = WidgetSettings.forget(context, ids)
}

class BareDigitalWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.bareDigital(context)
}

class BareSmallWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.bareSmall(context)
}

class BareAnalogWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.bareAnalog(context)
}

/**
 * What is set for one placed widget (lighter widgets handoff §6): whether the cookie of an analog
 * or a round digital clock is see-through. Kept by the widget's id, in storage that can be read
 * before the phone is first unlocked, as the widgets are drawn then too.
 */
object WidgetSettings {
    private fun store(context: Context) = context.createDeviceProtectedStorageContext().getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun seeThrough(context: Context, id: Int): Boolean = store(context).getBoolean(SEE_THROUGH + id, false)

    fun setSeeThrough(context: Context, id: Int, on: Boolean) = store(context).edit { if (on) putBoolean(SEE_THROUGH + id, true) else remove(SEE_THROUGH + id) }

    /** Removed widgets take their settings with them. */
    fun forget(context: Context, ids: IntArray) = store(context).edit { ids.forEach { remove(SEE_THROUGH + it) } }

    private const val SEE_THROUGH = "see_through_"
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
class BareWorldWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // The cities come from storage: the broadcast is held until the widget is drawn
        val pending = goAsync()
        Widgets.world(context) { pending.finish() }
    }
}

class TimerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.timer(context)
}

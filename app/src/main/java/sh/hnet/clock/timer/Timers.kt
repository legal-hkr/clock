package sh.hnet.clock.timer

import kotlinx.serialization.Serializable
import sh.hnet.clock.stopwatch.Moment

/**
 * A timer (SPEC §3.6), stored by its end so it keeps running with the app closed and after a
 * restart of the phone (§3.6.10). As the stopwatch, it counts by the time since boot within one
 * boot (unmoved by changes of the wall clock) and by the wall clock across a restart.
 *
 * @param original What it was set to; Reset returns to it
 * @param total The span the progress is measured against: [original] plus the minutes added
 * @param left Milliseconds left while paused
 * @param silenced Ended, rang unanswered and went quiet by itself; still counting, still a card
 */
@Serializable
data class Timer(
    val id: Long,
    val label: String = "",
    val original: Long,
    val total: Long = original,
    val running: Boolean = false,
    val left: Long = original,
    val endWall: Long = 0,
    val endSinceBoot: Long = 0,
    val boot: Int = 0,
    val silenced: Boolean = false,
) {
    /** Milliseconds left; zero or negative once it has ended (it then counts up below zero). */
    fun remaining(now: Moment): Long = when {
        !running -> left
        now.boot == boot -> endSinceBoot - now.sinceBoot
        else -> endWall - now.wall
    }

    fun ended(now: Moment): Boolean = running && remaining(now) <= 0

    /** Ended and still asking for attention. */
    fun ringing(now: Moment): Boolean = ended(now) && !silenced

    /** The wall-clock moment it ends (or ended); meaningful while running. */
    fun endsAt(now: Moment): Long = now.wall + remaining(now)

    /** The share of [total] still left, 1 at the start, 0 from the end on. */
    fun fraction(now: Moment): Float = if (total <= 0) 0f else (remaining(now).toFloat() / total).coerceIn(0f, 1f)

    internal fun runFor(ms: Long, now: Moment): Timer =
        copy(running = true, left = ms, endWall = now.wall + ms, endSinceBoot = now.sinceBoot + ms, boot = now.boot, silenced = false)

    fun resume(now: Moment): Timer = if (running) this else runFor(left, now)

    /** Holds the time left; an ended timer can't be paused. */
    fun pause(now: Moment): Timer = if (!running || ended(now)) this else copy(running = false, left = remaining(now))

    /** Back to the full time it was set to, paused (§3.6.4). */
    fun reset(): Timer = copy(running = false, left = original, total = original, endWall = 0, endSinceBoot = 0, boot = 0, silenced = false)

    /** One more minute; an ended timer starts again at 1:00 (§3.4.7). */
    fun addMinute(now: Moment): Timer = when {
        ended(now) -> copy(total = MINUTE).runFor(MINUTE, now)
        running -> copy(total = total + MINUTE).runFor(remaining(now) + MINUTE, now)
        else -> copy(total = total + MINUTE, left = left + MINUTE)
    }

    companion object {
        const val MINUTE = 60_000L

        /** 99 h 59 min 59 s, the most the input takes (§3.6.1). */
        const val MAX = (99 * 3600 + 59 * 60 + 59) * 1000L
    }
}

/** A duration the user keeps to start a timer with one tap (§3.6.2). */
@Serializable
data class TimerPreset(val id: Long, val name: String, val duration: Long)

/** The order timers are shown in (§3.6.5): the ended ones first, the one that ended first on top; then by time left. */
fun List<Timer>.shown(now: Moment): List<Timer> = sortedWith(compareBy({ !it.ended(now) }, { it.remaining(now) }, { it.id }))

/** How many timers are counting down, hold a time, or have ended; for the subtitle and the badge (§3.1.5, §3.6.5). */
data class TimerCounts(val running: Int, val paused: Int, val ended: Int) {
    companion object {
        fun of(timers: List<Timer>, now: Moment) = TimerCounts(
            running = timers.count { it.running && !it.ended(now) },
            paused = timers.count { !it.running },
            ended = timers.count { it.ended(now) },
        )
    }
}

/**
 * The duration being typed on the keypad (§3.6.1): up to six digits filling "00h 00m 00s" from the
 * right. Seconds and minutes above 59 are taken as typed ("90" seconds runs as 1:30).
 */
data class TimerInput(val digits: String = "") {
    val hours: String get() = padded.substring(0, 2)
    val minutes: String get() = padded.substring(2, 4)
    val seconds: String get() = padded.substring(4, 6)

    /** How many of the six places are still untyped (shown muted), from the left. */
    val untyped: Int get() = 6 - digits.length

    private val padded: String get() = digits.padStart(6, '0')

    /** The duration, capped at the most a timer takes. */
    val duration: Long get() = ((hours.toLong() * 3600 + minutes.toLong() * 60 + seconds.toLong()) * 1000).coerceAtMost(Timer.MAX)

    val isZero: Boolean get() = duration == 0L

    /** Typing [key] ("0"–"9" or "00"); leading zeros and digits beyond six are dropped. */
    fun type(key: String): TimerInput = TimerInput((digits + key).trimStart('0').take(6).let { if (it.length < (digits + key).trimStart('0').length) digits else it })

    fun backspace(): TimerInput = TimerInput(digits.dropLast(1))
}

/** Times of timers as text. */
object TimerFormat {
    /**
     * Time left as "6:42", "1:02:15" – rounded up to the second, so a timer shows 0:01 until it ends
     * – and, once ended, the time since as "−0:42" (§3.6.7).
     */
    fun left(remaining: Long): String {
        if (remaining > 0) return clock((remaining + 999) / 1000)
        return "−" + clock(-remaining / 1000)
    }

    private fun clock(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** A duration's parts for "3 min", "1 h 30 min", "45 s": hours, minutes, seconds (the words come from the resources). */
    fun parts(duration: Long): Triple<Int, Int, Int> {
        val seconds = duration / 1000
        return Triple((seconds / 3600).toInt(), (seconds % 3600 / 60).toInt(), (seconds % 60).toInt())
    }
}

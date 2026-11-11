package sh.hnet.clock.stopwatch

import kotlinx.serialization.Serializable

/**
 * A moment as the stopwatch needs it: the wall clock, the time since boot and which boot this is.
 * Within one boot the time since boot is used – it runs evenly whatever happens to the wall clock
 * (time zone, manual change, network correction); across a restart of the phone only the wall
 * clock is left to go by.
 */
data class Moment(val wall: Long, val sinceBoot: Long, val boot: Int)

/**
 * The stopwatch (SPEC §3.7), stored by its start so it keeps counting with the app closed and
 * after a restart of the phone (§3.7.6). Pure: every operation takes the moment it happens at.
 *
 * @param accumulated Milliseconds counted before the current run
 * @param laps The total at each lap mark, oldest first
 */
@Serializable
data class Stopwatch(
    val running: Boolean = false,
    val accumulated: Long = 0,
    val startedWall: Long = 0,
    val startedSinceBoot: Long = 0,
    val startedBoot: Int = 0,
    val laps: List<Long> = emptyList(),
) {
    /** At zero: never started, or reset. */
    val isZero: Boolean get() = !running && accumulated == 0L && laps.isEmpty()

    fun elapsed(now: Moment): Long {
        if (!running) return accumulated
        val run = if (now.boot == startedBoot) now.sinceBoot - startedSinceBoot else now.wall - startedWall
        return accumulated + run.coerceAtLeast(0)
    }

    fun start(now: Moment): Stopwatch =
        if (running) this else copy(running = true, startedWall = now.wall, startedSinceBoot = now.sinceBoot, startedBoot = now.boot)

    fun pause(now: Moment): Stopwatch = if (!running) this else copy(running = false, accumulated = elapsed(now))

    /** Marks a lap; only while running, and up to [MAX_LAPS]. */
    fun lap(now: Moment): Stopwatch = if (!running || laps.size >= MAX_LAPS) this else copy(laps = laps + elapsed(now))

    val canLap: Boolean get() = running && laps.size < MAX_LAPS

    /**
     * The lap list, newest first (§3.7.3): empty before the first lap mark; then the lap now being
     * timed (also while paused), then the finished ones. From two finished laps on, the fastest and
     * the slowest are marked – unless all took the same time; the unfinished lap is never marked.
     */
    fun rows(elapsed: Long): List<LapRow> {
        if (laps.isEmpty()) return emptyList()
        val times = laps.mapIndexed { index, total -> total - (if (index == 0) 0 else laps[index - 1]) }
        val fastest = times.min()
        val slowest = times.max()
        val marked = times.size >= 2 && fastest != slowest
        val finished = times.mapIndexed { index, time ->
            val mark = when {
                !marked -> null
                index == times.indexOf(fastest) -> LapMark.FASTEST
                index == times.indexOf(slowest) -> LapMark.SLOWEST
                else -> null
            }
            LapRow(index + 1, time, laps[index], current = false, mark = mark)
        }
        val current = LapRow(laps.size + 1, (elapsed - laps.last()).coerceAtLeast(0), elapsed, current = true, mark = null)
        return listOf(current) + finished.asReversed()
    }

    companion object {
        const val MAX_LAPS = 999
    }
}

enum class LapMark { FASTEST, SLOWEST }

/** A row of the lap list: its number, the lap's own time and the total at its end (so far, for the [current] one). */
data class LapRow(val number: Int, val time: Long, val total: Long, val current: Boolean, val mark: LapMark?)

/**
 * Stopwatch times as text (§3.7.1): "04:12" and ".37", from one hour on "1:02:15" and ".37".
 * Hundredths are cut, not rounded: a stopwatch never shows a time it hasn't reached.
 */
object StopwatchFormat {
    /** Minutes and seconds, with hours from one hour on. */
    fun main(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun hundredths(ms: Long): String = ".%02d".format(ms.coerceAtLeast(0) % 1000 / 10)

    fun full(ms: Long): String = main(ms) + hundredths(ms)

    /**
     * The laps as plain text for sharing (§3.7.5), oldest first, without the unfinished lap; the
     * total leads as "key: value". [title] and [lap] come from the resources ("Stopwatch", "Lap %d").
     */
    fun share(stopwatch: Stopwatch, elapsed: Long, title: String, lap: (Int) -> String): String = buildString {
        append(title).append(": ").append(full(elapsed))
        stopwatch.rows(elapsed).filter { !it.current }.asReversed().forEach { row ->
            append('\n').append(lap(row.number)).append("  ").append(full(row.time)).append("  ").append(full(row.total))
        }
    }
}

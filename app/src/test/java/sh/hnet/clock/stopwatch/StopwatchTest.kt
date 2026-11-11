package sh.hnet.clock.stopwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopwatchTest {

    /** A moment [ms] after an arbitrary start, within boot 7. */
    private fun at(ms: Long, boot: Int = 7) = Moment(wall = 1_800_000_000_000 + ms, sinceBoot = 50_000 + ms, boot = boot)

    @Test
    fun `a new stopwatch is at zero and counts from its start`() {
        val zero = Stopwatch()
        assertTrue(zero.isZero)
        assertEquals(0, zero.elapsed(at(5_000)))
        val running = zero.start(at(0))
        assertFalse(running.isZero)
        assertEquals(4_120, running.elapsed(at(4_120)))
    }

    @Test
    fun `a pause holds the time and a new start goes on from it`() {
        val paused = Stopwatch().start(at(0)).pause(at(3_000))
        assertEquals(3_000, paused.elapsed(at(60_000)))
        val again = paused.start(at(60_000))
        assertEquals(4_500, again.elapsed(at(61_500)))
    }

    @Test
    fun `starting twice or pausing twice changes nothing`() {
        val running = Stopwatch().start(at(0))
        assertEquals(running, running.start(at(900)))
        val paused = running.pause(at(1_000))
        assertEquals(paused, paused.pause(at(2_000)))
    }

    @Test
    fun `within one boot a changed wall clock doesn't matter`() {
        val running = Stopwatch().start(at(0))
        // The wall clock set back an hour, 10 s of real time later
        val later = Moment(wall = at(0).wall - 3_600_000, sinceBoot = at(10_000).sinceBoot, boot = 7)
        assertEquals(10_000, running.elapsed(later))
    }

    @Test
    fun `after a restart of the phone the wall clock carries the count`() {
        val running = Stopwatch().start(at(0))
        // A new boot: its own clock starts again near zero
        val afterRestart = Moment(wall = at(600_000).wall, sinceBoot = 12_000, boot = 8)
        assertEquals(600_000, running.elapsed(afterRestart))
        // And never below what was counted before, should the wall clock have gone back
        val earlier = Moment(wall = at(0).wall - 5_000, sinceBoot = 12_000, boot = 8)
        assertEquals(2_000, Stopwatch().start(at(0)).pause(at(2_000)).start(at(3_000)).elapsed(earlier))
    }

    @Test
    fun `laps are marked only while running`() {
        val paused = Stopwatch().start(at(0)).pause(at(1_000))
        assertEquals(paused, paused.lap(at(2_000)))
        assertFalse(paused.canLap)
        assertEquals(listOf(1_500L), Stopwatch().start(at(0)).lap(at(1_500)).laps)
    }

    @Test
    fun `no lap list before the first lap`() {
        assertTrue(Stopwatch().start(at(0)).rows(5_000).isEmpty())
    }

    // Board 11b: laps of 43.50, 43.60, 48.90, 41.15, 44.02 and 31.20 s into the sixth
    private val board = Stopwatch(running = true, laps = listOf(43_500, 87_100, 136_000, 177_150, 221_170))

    @Test
    fun `rows come newest first, the lap now timed on top`() {
        val rows = board.rows(252_370)
        assertEquals(listOf(6, 5, 4, 3, 2, 1), rows.map { it.number })
        assertEquals(LapRow(6, 31_200, 252_370, current = true, mark = null), rows[0])
        assertEquals(listOf(44_020L, 41_150L, 48_900L, 43_600L, 43_500L), rows.drop(1).map { it.time })
        assertEquals(listOf(221_170L, 177_150L, 136_000L, 87_100L, 43_500L), rows.drop(1).map { it.total })
    }

    @Test
    fun `the fastest and slowest finished laps are marked`() {
        val marks = board.rows(252_370).associate { it.number to it.mark }
        assertEquals(LapMark.FASTEST, marks[4])
        assertEquals(LapMark.SLOWEST, marks[3])
        assertEquals(setOf(null, LapMark.FASTEST, LapMark.SLOWEST), marks.values.toSet())
        assertEquals(null, marks[6])
    }

    @Test
    fun `one finished lap, or laps all alike, get no marks`() {
        assertTrue(Stopwatch(running = true, laps = listOf(10_000)).rows(12_000).all { it.mark == null })
        assertTrue(Stopwatch(running = true, laps = listOf(10_000, 20_000, 30_000)).rows(31_000).all { it.mark == null })
    }

    @Test
    fun `of laps tied for fastest the first one is marked`() {
        val rows = Stopwatch(running = true, laps = listOf(10_000, 25_000, 35_000)).rows(36_000)
        assertEquals(LapMark.FASTEST, rows.first { it.number == 1 }.mark)
        assertEquals(null, rows.first { it.number == 3 }.mark)
        assertEquals(LapMark.SLOWEST, rows.first { it.number == 2 }.mark)
    }

    @Test
    fun `times are written with hundredths cut, and hours from one hour on`() {
        assertEquals("00:00", StopwatchFormat.main(0))
        assertEquals(".00", StopwatchFormat.hundredths(0))
        assertEquals("04:12.37", StopwatchFormat.full(252_370))
        assertEquals("04:12.37", StopwatchFormat.full(252_379))
        assertEquals("59:59.99", StopwatchFormat.full(3_599_999))
        assertEquals("1:00:00.00", StopwatchFormat.full(3_600_000))
        assertEquals("1:02:15.37", StopwatchFormat.full(3_735_370))
        assertEquals("100:00:00.00", StopwatchFormat.full(360_000_000))
    }

    @Test
    fun `shared text lists the finished laps oldest first under the total`() {
        val text = StopwatchFormat.share(Stopwatch(running = true, laps = listOf(43_500, 87_100)), 100_000, "Stopwatch") { "Lap $it" }
        assertEquals("Stopwatch: 01:40.00\nLap 1  00:43.50  00:43.50\nLap 2  00:43.60  01:27.10", text)
        assertEquals("Stopwatch: 00:05.00", StopwatchFormat.share(Stopwatch(running = true), 5_000, "Stopwatch") { "Lap $it" })
    }

    @Test
    fun `no more laps than the limit`() {
        val full = Stopwatch(running = true, laps = List(Stopwatch.MAX_LAPS) { (it + 1) * 1_000L })
        assertFalse(full.canLap)
        assertEquals(full, full.lap(at(5_000_000)))
    }
}

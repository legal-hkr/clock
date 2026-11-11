package sh.hnet.clock.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.clock.stopwatch.Moment

class TimersTest {

    private fun at(ms: Long, boot: Int = 7) = Moment(wall = 1_800_000_000_000 + ms, sinceBoot = 50_000 + ms, boot = boot)

    private val tea = Timer(id = 1, label = "Tea", original = 180_000)

    @Test
    fun `a new timer holds its full time until it is started`() {
        assertEquals(180_000, tea.remaining(at(99_000)))
        assertFalse(tea.ended(at(999_000)))
        assertEquals(1f, tea.fraction(at(0)))
    }

    @Test
    fun `a running timer counts down to its end and then below zero`() {
        val running = tea.resume(at(0))
        assertEquals(120_000, running.remaining(at(60_000)))
        assertEquals(1f / 3, running.fraction(at(120_000)), 0.0001f)
        assertFalse(running.ended(at(179_999)))
        assertTrue(running.ended(at(180_000)))
        assertEquals(-42_000, running.remaining(at(222_000)))
        assertEquals(0f, running.fraction(at(222_000)))
        assertTrue(running.ringing(at(222_000)))
        assertFalse(running.copy(silenced = true).ringing(at(222_000)))
    }

    @Test
    fun `a pause holds the time left and a resume ends that much later`() {
        val paused = tea.resume(at(0)).pause(at(30_000))
        assertEquals(150_000, paused.remaining(at(500_000)))
        val again = paused.resume(at(500_000))
        assertEquals(at(650_000).wall, again.endsAt(at(500_000)))
        assertTrue(again.ended(at(650_000)))
    }

    @Test
    fun `an ended timer can't be paused`() {
        val running = tea.resume(at(0))
        assertEquals(running, running.pause(at(200_000)))
    }

    @Test
    fun `within one boot a changed wall clock doesn't move the end`() {
        val running = tea.resume(at(0))
        val wallSetBack = Moment(wall = at(0).wall - 3_600_000, sinceBoot = at(60_000).sinceBoot, boot = 7)
        assertEquals(120_000, running.remaining(wallSetBack))
    }

    @Test
    fun `after a restart of the phone the wall clock decides, also for a timer that ended meanwhile`() {
        val running = tea.resume(at(0))
        assertEquals(60_000, running.remaining(Moment(wall = at(120_000).wall, sinceBoot = 9_000, boot = 8)))
        // The phone was off over the end: ended, counting up from its real end (§3.6.10)
        val late = Moment(wall = at(600_000).wall, sinceBoot = 9_000, boot = 8)
        assertTrue(running.ended(late))
        assertEquals(-420_000, running.remaining(late))
    }

    @Test
    fun `reset returns to the time it was set to, paused, whatever was added`() {
        val changed = tea.resume(at(0)).addMinute(at(10_000))
        assertEquals(tea, changed.reset())
        assertEquals(tea, tea.resume(at(0)).copy(silenced = true).reset())
    }

    @Test
    fun `a minute more lengthens a running or paused timer and its span`() {
        val running = tea.resume(at(0)).addMinute(at(60_000))
        assertEquals(180_000, running.remaining(at(60_000)))
        assertEquals(240_000, running.total)
        val paused = tea.resume(at(0)).pause(at(60_000)).addMinute(at(70_000))
        assertFalse(paused.running)
        assertEquals(180_000, paused.remaining(at(70_000)))
        assertEquals(240_000, paused.total)
    }

    @Test
    fun `a minute more on an ended timer starts it again at one minute`() {
        val again = tea.resume(at(0)).copy(silenced = true).addMinute(at(222_000))
        assertEquals(60_000, again.remaining(at(222_000)))
        assertEquals(60_000, again.total)
        assertFalse(again.silenced)
        assertFalse(again.ended(at(222_000)))
        assertEquals(180_000, again.original)
    }

    @Test
    fun `ended timers are shown first, earliest end on top, then by time left`() {
        val now = at(1_000_000)
        val pasta = Timer(2, "Pasta", original = 540_000).resume(at(900_000))
        val eggs = Timer(3, "Eggs", original = 360_000).resume(at(600_000))
        val sauce = Timer(4, "Sauce", original = 60_000).resume(at(100_000))
        val paused = Timer(5, "Rice", original = 75_000)
        assertEquals(listOf("Sauce", "Eggs", "Rice", "Pasta"), listOf(pasta, paused, eggs, sauce).shown(now).map { it.label })
        assertEquals(TimerCounts(running = 1, paused = 1, ended = 2), TimerCounts.of(listOf(pasta, paused, eggs, sauce), now))
    }

    @Test
    fun `the keypad fills the duration from the right`() {
        var input = TimerInput()
        assertTrue(input.isZero)
        assertEquals(6, input.untyped)
        input = input.type("5").type("3").type("0")
        assertEquals(Triple("00", "05", "30"), Triple(input.hours, input.minutes, input.seconds))
        assertEquals(330_000, input.duration)
        assertEquals(3, input.untyped)
        assertEquals("53", input.backspace().digits)
    }

    @Test
    fun `leading zeros type nothing and a seventh digit is refused`() {
        assertEquals("", TimerInput().type("0").type("00").digits)
        assertEquals("100", TimerInput().type("1").type("00").digits)
        val full = TimerInput("123456")
        assertEquals(full, full.type("7"))
        assertEquals(TimerInput("12345"), TimerInput("12345").type("00"))
    }

    @Test
    fun `seconds and minutes above 59 run as typed, up to the most a timer takes`() {
        assertEquals(90_000, TimerInput("90").duration)
        assertEquals((99 * 60 + 99) * 1000L, TimerInput("9999").duration)
        assertEquals(Timer.MAX, TimerInput("999999").duration)
    }

    @Test
    fun `time left is rounded up to the second, time since the end counts up after a minus`() {
        assertEquals("6:42", TimerFormat.left(402_000))
        assertEquals("6:42", TimerFormat.left(401_001))
        assertEquals("0:01", TimerFormat.left(1))
        assertEquals("1:02:15", TimerFormat.left(3_735_000))
        assertEquals("−0:00", TimerFormat.left(0))
        assertEquals("−0:42", TimerFormat.left(-42_999))
        assertEquals("−1:00:05", TimerFormat.left(-3_605_000))
    }

    @Test
    fun `a duration splits into hours, minutes and seconds`() {
        assertEquals(Triple(0, 3, 0), TimerFormat.parts(180_000))
        assertEquals(Triple(1, 30, 0), TimerFormat.parts(5_400_000))
        assertEquals(Triple(0, 0, 45), TimerFormat.parts(45_000))
    }
}

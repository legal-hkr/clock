package sh.hnet.clock.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityTest {
    private val fine = Checks(notifications = true, alarmChannel = true, timerChannel = true, fullScreen = true, totalSilence = false, restricted = false, volume = 5, maxVolume = 7)

    @Test
    fun `nothing wrong, nothing reported`() {
        assertEquals(emptyList<Problem>(), fine.problems(Ringing.ANY, soundWanted = true))
    }

    @Test
    fun `problems come in the order they are shown`() {
        val all = Checks(notifications = false, alarmChannel = true, timerChannel = true, fullScreen = false, totalSilence = true, restricted = true, volume = 0, maxVolume = 7)
        assertEquals(Problem.entries.toList(), all.problems(Ringing.ALARMS, soundWanted = true))
    }

    @Test
    fun `a blocked ringing channel stops only what rings through it`() {
        val blocked = fine.copy(timerChannel = false)
        assertTrue(blocked.canNotify(Ringing.ALARMS))
        assertFalse(blocked.canNotify(Ringing.TIMERS))
        assertEquals(emptyList<Problem>(), blocked.problems(Ringing.ALARMS, soundWanted = true))
        assertEquals(listOf(Problem.NOTIFICATIONS), blocked.problems(Ringing.TIMERS, soundWanted = true))
        assertEquals(listOf(Problem.NOTIFICATIONS), blocked.problems(Ringing.ANY, soundWanted = true))
    }

    @Test
    fun `notifications off as a whole stop both`() {
        val off = fine.copy(notifications = false)
        assertFalse(off.canNotify(Ringing.ALARMS))
        assertFalse(off.canNotify(Ringing.TIMERS))
    }

    @Test
    fun `zero volume matters only when a sound is wanted`() {
        val zero = fine.copy(volume = 0)
        assertEquals(listOf(Problem.VOLUME), zero.problems(Ringing.ALARMS, soundWanted = true))
        assertEquals(emptyList<Problem>(), zero.problems(Ringing.ALARMS, soundWanted = false))
    }

    @Test
    fun `volume as a share of its range`() {
        assertEquals(71, fine.volumePercent)
        assertEquals(0, fine.copy(volume = 0).volumePercent)
        assertEquals(100, fine.copy(volume = 7).volumePercent)
        assertEquals(0, fine.copy(maxVolume = 0).volumePercent)
    }
}

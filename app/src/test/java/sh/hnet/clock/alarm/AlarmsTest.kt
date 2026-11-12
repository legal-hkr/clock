package sh.hnet.clock.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmsTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")

    /** A local moment in Warsaw; 11 November 2026 is a Wednesday. */
    private fun at(text: String, zone: ZoneId = warsaw): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private val minute = 60_000L
    private val tenMinutes = 10 * minute

    private val weekdays = setOf(1, 2, 3, 4, 5)
    private val work = Alarm(id = 1, hour = 6, minute = 30, days = weekdays, label = "Work", since = at("2026-11-11T23:18"))
    private val once = Alarm(id = 2, hour = 8, minute = 0, since = at("2026-11-11T23:18"))

    @Test
    fun `a repeating alarm rings on its next day`() {
        assertEquals(at("2026-11-12T06:30"), work.next(warsaw))
        // Friday evening: Monday comes next
        assertEquals(at("2026-11-16T06:30"), work.copy(since = at("2026-11-13T07:00")).next(warsaw))
        // The very minute it rang is behind it
        assertEquals(at("2026-11-13T06:30"), work.copy(since = at("2026-11-12T06:30")).next(warsaw))
    }

    @Test
    fun `a one-off alarm without a date rings today or tomorrow, whichever comes first`() {
        assertEquals(at("2026-11-12T08:00"), once.next(warsaw))
        assertEquals(at("2026-11-11T08:00"), once.copy(since = at("2026-11-11T07:59")).next(warsaw))
    }

    @Test
    fun `a one-off alarm with a date rings on it, and never after`() {
        val flight = once.copy(hour = 14, minute = 0, date = "2026-11-20")
        assertEquals(at("2026-11-20T14:00"), flight.next(warsaw))
        assertNull(flight.copy(since = at("2026-11-20T14:00")).next(warsaw))
    }

    @Test
    fun `an alarm that is off has no next ringing`() {
        assertNull(work.off().next(warsaw))
        assertNull(work.off().pending(warsaw))
    }

    @Test
    fun `switched on, a date already past is dropped`() {
        val past = once.copy(date = "2026-11-10", enabled = false).on(at("2026-11-11T23:18"), warsaw)
        assertNull(past.date)
        assertEquals(at("2026-11-12T08:00"), past.next(warsaw))
        assertEquals("2026-11-20", once.copy(date = "2026-11-20").on(at("2026-11-11T23:18"), warsaw).date)
    }

    @Test
    fun `the alarm keeps its wall-clock time in another time zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 23:18 on Wednesday in Warsaw is 07:18 on Thursday in Tokyo: today's 06:30 there is gone
        assertEquals(at("2026-11-13T06:30", tokyo), work.next(tokyo))
        // Flying west with the alarm still ahead today
        val newYork = ZoneId.of("America/New_York")
        assertEquals(at("2026-11-12T06:30", newYork), work.copy(since = at("2026-11-12T05:00")).next(newYork))
    }

    @Test
    fun `a time the clocks skip in spring rings when the gap is over`() {
        // 29 March 2026, Warsaw: 02:00 becomes 03:00
        val night = Alarm(id = 3, hour = 2, minute = 30, since = at("2026-03-28T12:00"))
        assertEquals(at("2026-03-29T03:30"), night.next(warsaw))
    }

    @Test
    fun `skipping leaves out one ringing and nothing more`() {
        val skipping = work.skipNext(warsaw)
        assertEquals(at("2026-11-12T06:30"), skipping.skipped)
        assertEquals(at("2026-11-13T06:30"), skipping.next(warsaw))
        assertTrue(skipping.enabled)
        assertEquals(at("2026-11-12T06:30"), skipping.unskip().next(warsaw))
        // Its time passed: the skip is behind it, the alarm goes on as ever
        val after = skipping.advance(at("2026-11-12T06:31"), warsaw, tenMinutes, tenMinutes)
        assertNull(after.skipped)
        assertNull(after.ringing)
        assertEquals(at("2026-11-13T06:30"), after.next(warsaw))
    }

    @Test
    fun `a one-off alarm can't be skipped, only dismissed`() {
        assertEquals(once, once.skipNext(warsaw))
        assertFalse(once.dismissUpcoming(warsaw).enabled)
        assertEquals(at("2026-11-13T06:30"), work.dismissUpcoming(warsaw).next(warsaw))
    }

    @Test
    fun `at its time the alarm starts to ring and looks to the next day`() {
        assertEquals(work, work.advance(at("2026-11-12T06:29"), warsaw, tenMinutes, tenMinutes))
        val ringing = work.advance(at("2026-11-12T06:30"), warsaw, tenMinutes, tenMinutes)
        assertEquals(at("2026-11-12T06:30"), ringing.ringing)
        assertNull(ringing.pending(warsaw))
        assertEquals(at("2026-11-13T06:30"), ringing.next(warsaw))
    }

    @Test
    fun `stopped, a repeating alarm stays on and a one-off one switches off`() {
        val stopped = work.advance(at("2026-11-12T06:30"), warsaw, tenMinutes, tenMinutes).stop()
        assertTrue(stopped.enabled)
        assertNull(stopped.ringing)
        assertEquals(at("2026-11-13T06:30"), stopped.pending(warsaw))
        val onceStopped = once.advance(at("2026-11-12T08:00"), warsaw, tenMinutes, tenMinutes).stop()
        assertFalse(onceStopped.enabled)
    }

    @Test
    fun `snoozed, it rings again when the snooze ends, as often as wanted`() {
        var alarm = work.advance(at("2026-11-12T06:30"), warsaw, tenMinutes, tenMinutes).snoozed(at("2026-11-12T06:41"))
        assertNull(alarm.ringing)
        assertEquals(at("2026-11-12T06:41"), alarm.pending(warsaw))
        alarm = alarm.advance(at("2026-11-12T06:41"), warsaw, tenMinutes, tenMinutes)
        assertEquals(at("2026-11-12T06:41"), alarm.ringing)
        assertNull(alarm.snoozedUntil)
        alarm = alarm.snoozed(at("2026-11-12T06:52")).advance(at("2026-11-12T06:52"), warsaw, tenMinutes, tenMinutes)
        assertEquals(at("2026-11-12T06:52"), alarm.ringing)
        // Dismissed while snoozed: over for today
        val dismissed = work.advance(at("2026-11-12T06:30"), warsaw, tenMinutes, tenMinutes).snoozed(at("2026-11-12T06:41")).stop()
        assertEquals(at("2026-11-13T06:30"), dismissed.pending(warsaw))
    }

    @Test
    fun `unanswered for the silence time, it stops and counts as missed`() {
        val ringing = work.advance(at("2026-11-12T06:30"), warsaw, tenMinutes, tenMinutes)
        assertEquals(ringing, ringing.advance(at("2026-11-12T06:39"), warsaw, tenMinutes, tenMinutes))
        val missed = ringing.advance(at("2026-11-12T06:40"), warsaw, tenMinutes, tenMinutes)
        assertNull(missed.ringing)
        assertNull(missed.snoozedUntil)
        assertEquals(at("2026-11-12T06:30"), missed.missed)
        assertTrue(missed.enabled)
        // A one-off alarm switches off when missed
        val onceMissed = once.advance(at("2026-11-12T08:00"), warsaw, tenMinutes, tenMinutes).advance(at("2026-11-12T08:10"), warsaw, tenMinutes, tenMinutes)
        assertFalse(onceMissed.enabled)
        assertEquals(at("2026-11-12T08:00"), onceMissed.missed)
    }

    @Test
    fun `with silence set to never it rings until answered`() {
        val ringing = work.advance(at("2026-11-12T06:30"), warsaw, 0, 30 * minute)
        assertEquals(ringing, ringing.advance(at("2026-11-12T16:30"), warsaw, 0, 30 * minute))
    }

    @Test
    fun `a moment only just jumped over rings at once`() {
        // The phone came back on five minutes late
        val late = work.advance(at("2026-11-12T06:35"), warsaw, tenMinutes, tenMinutes)
        assertEquals(at("2026-11-12T06:30"), late.ringing)
        assertEquals(at("2026-11-13T06:30"), late.next(warsaw))
    }

    @Test
    fun `ringings far behind were missed, and the alarm goes on from now`() {
        // Off from Wednesday night to Monday noon: Thursday's, Friday's and Monday's ringings are behind
        val back = work.advance(at("2026-11-16T12:00"), warsaw, tenMinutes, tenMinutes)
        assertNull(back.ringing)
        assertEquals(at("2026-11-16T06:30"), back.missed)
        assertEquals(at("2026-11-17T06:30"), back.pending(warsaw))
        val onceBack = once.advance(at("2026-11-16T12:00"), warsaw, tenMinutes, tenMinutes)
        assertFalse(onceBack.enabled)
        assertEquals(at("2026-11-12T08:00"), onceBack.missed)
    }

    @Test
    fun `a snooze that ended with the phone off was missed, the next day stays`() {
        val snoozed = work.advance(at("2026-11-12T06:30"), warsaw, tenMinutes, tenMinutes).snoozed(at("2026-11-12T06:41"))
        val back = snoozed.advance(at("2026-11-12T09:00"), warsaw, tenMinutes, tenMinutes)
        assertNull(back.ringing)
        assertEquals(at("2026-11-12T06:41"), back.missed)
        assertEquals(at("2026-11-13T06:30"), back.pending(warsaw))
    }

    @Test
    fun `changing the time of an alarm that is on starts it afresh`() {
        val snoozed = work.advance(at("2026-11-12T06:30"), warsaw, tenMinutes, tenMinutes).snoozed(at("2026-11-12T06:41"))
        val changed = snoozed.copy(hour = 7, minute = 0).rescheduled(at("2026-11-12T06:35"), warsaw)
        assertNull(changed.snoozedUntil)
        assertEquals(at("2026-11-12T07:00"), changed.pending(warsaw))
        // Off stays off
        assertFalse(work.off().copy(hour = 7).rescheduled(at("2026-11-12T06:35"), warsaw).enabled)
    }

    @Test
    fun `the earliest pending moment of all alarms comes next, the list is by time of day`() {
        val medication = Alarm(id = 3, hour = 22, minute = 30, days = (1..7).toSet(), since = at("2026-11-11T23:18"))
        val all = listOf(medication, once, work, work.copy(id = 9).off())
        assertEquals(work to at("2026-11-12T06:30"), all.nextPending(warsaw))
        assertEquals(listOf(1L, 9L, 2L, 3L), all.shown().map { it.id })
        assertNull(listOf(work.off()).nextPending(warsaw))
        // A snooze counts with its own end
        val snoozed = medication.snoozed(at("2026-11-11T23:25"))
        assertEquals(snoozed to at("2026-11-11T23:25"), listOf(work, snoozed).nextPending(warsaw))
    }

    @Test
    fun `rings in the two largest units, minutes rounded up`() {
        assertEquals(RingsIn(0, 7, 12), RingsIn.of(7 * 60 * minute + 12 * minute))
        assertEquals(RingsIn(0, 7, 12), RingsIn.of(7 * 60 * minute + 11 * minute + 1))
        assertEquals(RingsIn(2, 3, 0), RingsIn.of(2 * 1440 * minute + 3 * 60 * minute + 40 * minute))
        assertEquals(RingsIn(0, 0, 1), RingsIn.of(minute))
        assertEquals(RingsIn(1, 0, 0), RingsIn.of(1440 * minute))
        assertEquals(RingsIn(0, 1, 0), RingsIn.of(59 * minute + 1))
        assertTrue(RingsIn.of(59_999).lessThanMinute)
        assertTrue(RingsIn.of(-5).lessThanMinute)
    }

    @Test
    fun `days read as every day, weekdays, weekends or a list`() {
        val weekend = setOf(6, 7)
        assertEquals(DaysKind.EVERY_DAY, Days.kind((1..7).toSet(), weekend))
        assertEquals(DaysKind.WEEKDAYS, Days.kind(weekdays, weekend))
        assertEquals(DaysKind.WEEKENDS, Days.kind(weekend, weekend))
        assertEquals(DaysKind.CUSTOM, Days.kind(setOf(1, 3, 5), weekend))
        // Where the weekend is Friday and Saturday
        assertEquals(DaysKind.WEEKENDS, Days.kind(setOf(5, 6), setOf(5, 6)))
        assertEquals(DaysKind.WEEKDAYS, Days.kind(setOf(7, 1, 2, 3, 4), setOf(5, 6)))
        assertEquals(listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY), Days.week(DayOfWeek.SUNDAY).take(2))
        assertEquals(DayOfWeek.SUNDAY, Days.week(DayOfWeek.MONDAY).last())
    }

    @Test
    fun `a clock set back does not pass over the next ringing`() {
        // Set while the clock ran two hours ahead, then corrected: today's 06:30 still lies ahead
        val set = work.copy(since = at("2026-11-12T07:00"))
        val now = at("2026-11-12T05:00")
        val advanced = set.advance(now, warsaw, tenMinutes, tenMinutes)
        assertEquals(now, advanced.since)
        assertEquals(at("2026-11-12T06:30"), advanced.next(warsaw))
    }

    @Test
    fun `a clock set back does not stretch a snooze or a ringing`() {
        val now = at("2026-11-12T05:00")
        val snoozed = work.copy(snoozedUntil = at("2026-11-12T06:40")).advance(now, warsaw, tenMinutes, tenMinutes)
        assertEquals(now + 30 * minute, snoozed.snoozedUntil)
        // A snooze within reach stays as it is
        val near = work.copy(snoozedUntil = now + 5 * minute).advance(now, warsaw, tenMinutes, tenMinutes)
        assertEquals(now + 5 * minute, near.snoozedUntil)
        val ringing = work.copy(ringing = at("2026-11-12T06:30")).advance(now, warsaw, tenMinutes, tenMinutes)
        assertEquals(now, ringing.ringing)
    }
}

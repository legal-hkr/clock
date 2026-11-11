package sh.hnet.clock.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class CitiesTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")

    private fun at(text: String, zone: ZoneId = warsaw): Instant = ZonedDateTime.parse("${text}:00+00:00[UTC]").withZoneSameLocal(zone).toInstant()

    private fun difference(zone: String, at: Instant, local: ZoneId = warsaw) = TimeDifference.between(local, ZoneId.of(zone), at)

    // Board 07a: Warsaw, Wednesday 11 November 2026, 23:18
    private val evening = at("2026-11-11T23:18")

    @Test
    fun `a city west of here is behind, on the same day`() {
        assertEquals(TimeDifference(-60, 0), difference("Atlantic/Reykjavik", evening))
        assertEquals(TimeDifference(-360, 0), difference("America/New_York", evening))
    }

    @Test
    fun `a city east of here late in the evening is ahead and already in tomorrow`() {
        assertEquals(TimeDifference(480, 1), difference("Asia/Tokyo", evening))
        assertEquals(TimeDifference(600, 1), difference("Australia/Sydney", evening))
    }

    @Test
    fun `half-hour and quarter-hour zones keep their minutes`() {
        assertEquals(TimeDifference(270, 1), difference("Asia/Kolkata", evening))
        assertEquals(TimeDifference(285, 1), difference("Asia/Kathmandu", evening))
        assertEquals(TimeDifference(15, 0), difference("Asia/Kathmandu", evening, ZoneId.of("Asia/Kolkata")))
    }

    @Test
    fun `early in the morning the west is still in yesterday`() {
        val morning = at("2026-11-12T03:00")
        assertEquals(TimeDifference(-360, -1), difference("America/New_York", morning))
        assertEquals(TimeDifference(480, 0), difference("Asia/Tokyo", morning))
    }

    @Test
    fun `a zone on the same time has no difference`() {
        assertEquals(TimeDifference(0, 0), difference("Europe/Berlin", evening))
        assertEquals(TimeDifference(0, 0), difference("Europe/Warsaw", evening))
    }

    @Test
    fun `the difference follows daylight saving time on each side`() {
        // The USA switches two to three weeks before Europe in spring: 5 hours for that while, not 6
        assertEquals(-300, difference("America/New_York", at("2026-03-20T12:00")).minutes)
        assertEquals(-360, difference("America/New_York", at("2026-04-20T12:00")).minutes)
        // A zone without daylight saving time against one with it
        assertEquals(480, difference("Asia/Tokyo", at("2026-01-15T12:00")).minutes)
        assertEquals(420, difference("Asia/Tokyo", at("2026-07-15T12:00")).minutes)
    }

    @Test
    fun `across the date line the date can be two days away`() {
        val pagoPago = ZoneId.of("Pacific/Pago_Pago")
        val late = at("2026-11-11T23:30", pagoPago)
        assertEquals(TimeDifference(25 * 60, 2), difference("Pacific/Kiritimati", late, pagoPago))
    }

    @Test
    fun `an id this system doesn't know is no zone`() {
        assertNull(zoneOrNull("Mars/Olympus_Mons"))
        assertNull(zoneOrNull(""))
        assertEquals(ZoneId.of("Asia/Kolkata").rules, zoneOrNull("Asia/Calcutta")?.rules)
    }

    @Test
    fun `away means the home clock is on and shows another time than here`() {
        val reykjavik = ZoneId.of("Atlantic/Reykjavik")
        assertTrue(isAway(true, warsaw, reykjavik, evening))
        assertFalse(isAway(false, warsaw, reykjavik, evening))
        assertFalse(isAway(true, null, reykjavik, evening))
        assertFalse(isAway(true, warsaw, warsaw, evening))
        // Another zone on the same time: the row would only repeat the local time
        assertFalse(isAway(true, warsaw, ZoneId.of("Europe/Berlin"), evening))
        // London is away from Reykjavík in summer only
        assertTrue(isAway(true, reykjavik, ZoneId.of("Europe/London"), at("2026-07-01T12:00")))
        assertFalse(isAway(true, reykjavik, ZoneId.of("Europe/London"), at("2026-12-01T12:00")))
    }

    private val cities = listOf(
        City("America/Chicago", "Chicago", "United States", "CST", listOf("CDT")),
        City("America/Indiana/Indianapolis", "Indianapolis", "United States", "EST", listOf("EDT")),
        City("Asia/Kolkata", "Kolkata", "India", "IST"),
        City("America/Sao_Paulo", "São Paulo", "Brazil", "GMT-3"),
        City("Atlantic/Reykjavik", "Reykjavík", "Iceland", "GMT"),
        City("Europe/Warsaw", "Warsaw", "Poland", "CET", listOf("CEST", "GMT+1")),
        City("Europe/Zurich", "Zurich", "Switzerland", "CET", listOf("CEST", "GMT+1")),
    )
    private val search = CitySearch(cities)

    private fun names(query: String) = search.search(query).map { it.name }

    @Test
    fun `an empty query gives every city in the given order`() {
        assertEquals(cities, search.search(""))
        assertEquals(cities, search.search("  "))
    }

    @Test
    fun `case and diacritics don't matter`() {
        assertEquals(listOf("Reykjavík"), names("reykjavik"))
        assertEquals(listOf("São Paulo"), names("SAO"))
        assertEquals(listOf("Warsaw"), names("wars"))
    }

    @Test
    fun `any word of a name can be started, but not its middle`() {
        assertEquals(listOf("São Paulo"), names("paulo"))
        assertEquals(emptyList<String>(), names("aulo"))
    }

    @Test
    fun `countries and zone abbreviations find their cities`() {
        assertEquals(listOf("Warsaw"), names("pol"))
        assertEquals(listOf("Warsaw", "Zurich"), names("cet"))
        assertEquals(listOf("Warsaw", "Zurich"), names("cest"))
        assertEquals(listOf("Chicago", "Indianapolis"), names("united"))
    }

    @Test
    fun `city names come before countries, countries before abbreviations`() {
        // Indianapolis by its name, Kolkata by India, both before Kolkata's IST would count
        assertEquals(listOf("Indianapolis", "Kolkata"), names("in"))
        assertEquals(listOf("Indianapolis", "Kolkata"), names("i").take(2))
    }

    @Test
    fun `every typed word must match something`() {
        assertEquals(listOf("Warsaw"), names("warsaw poland"))
        assertEquals(listOf("Zurich"), names("cet switz"))
        assertEquals(emptyList<String>(), names("warsaw brazil"))
        assertEquals(emptyList<String>(), names("lodz"))
    }

    @Test
    fun `reordering the visible cities leaves hidden ones in place`() {
        val stored = listOf("A", "hidden", "B", "C")
        assertEquals(listOf("C", "hidden", "A", "B"), mergeOrder(stored, listOf("C", "A", "B")))
        assertEquals(listOf("B", "A"), mergeOrder(listOf("A", "B"), listOf("B", "A")))
        assertEquals(stored, mergeOrder(stored, emptyList()))
    }
}

package sh.hnet.clock.time

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A place from the system's time zone list: one city per zone, as the system names it (SPEC §3.5.9).
 *
 * @param abbreviation The zone's short name now ("CET"), or its offset ("GMT+5:30") when it has none
 * @param otherAbbreviations Short names it also goes by (its summer or winter one), for the search only
 */
data class City(
    val zoneId: String,
    val name: String,
    val country: String,
    val abbreviation: String,
    val otherAbbreviations: List<String> = emptyList(),
)

/**
 * How a place's clock stands against the local one at a moment.
 *
 * @param minutes Its offset minus the local one: positive when its clock is ahead
 * @param dayShift Its calendar date minus the local date, in days (−1 yesterday, 1 tomorrow; ±2 at the date line's extremes)
 */
data class TimeDifference(val minutes: Int, val dayShift: Int) {
    companion object {
        /** An unknown zone id counts as the local zone: no difference, rather than a crash on stored data. */
        fun between(local: ZoneId, other: ZoneId, at: Instant): TimeDifference {
            val seconds = other.rules.getOffset(at).totalSeconds - local.rules.getOffset(at).totalSeconds
            val days = ChronoUnit.DAYS.between(at.atZone(local).toLocalDate(), at.atZone(other).toLocalDate())
            return TimeDifference(seconds / 60, days.toInt())
        }
    }
}

/** The zone for a stored id; null when this system doesn't know it (data from another phone or an older list). */
fun zoneOrNull(id: String): ZoneId? = try {
    ZoneId.of(id)
} catch (_: java.time.DateTimeException) {
    null
}

/**
 * The city search (SPEC §3.5.7): case- and diacritics-insensitive ("reykjavik" finds "Reykjavík");
 * every typed word must start a word of the city's name, its country or one of its zone
 * abbreviations. Matches in city names come first, then in country names, then in abbreviations;
 * within each, the order the cities came in (alphabetical). An empty query gives every city.
 */
class CitySearch(private val cities: List<City>) {
    private class Entry(val city: City, val fields: List<List<String>>)

    private val entries = cities.map { city ->
        Entry(city, listOf(words(city.name), words(city.country), words(city.abbreviation) + city.otherAbbreviations.flatMap(::words)))
    }

    fun search(query: String): List<City> {
        val terms = words(query)
        if (terms.isEmpty()) return cities
        val ranked = List(FIELDS) { ArrayList<City>() }
        for (entry in entries) {
            var best = FIELDS
            val matches = terms.all { term ->
                val field = entry.fields.indexOfFirst { words -> words.any { it.startsWith(term) } }
                if (field >= 0 && field < best) best = field
                field >= 0
            }
            if (matches) ranked[best] += entry.city
        }
        return ranked.flatten()
    }

    companion object {
        private const val FIELDS = 3
        private val MARKS = Regex("\\p{Mn}+")
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        /** Lower case without diacritics; letters that don't decompose (ł, ø, đ, ß) mapped by hand. As Music's search. */
        fun normalize(text: String): String {
            val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
            return buildString(decomposed.length) {
                for (c in decomposed) when (c) {
                    'ł' -> append('l')
                    'ø' -> append('o')
                    'đ' -> append('d')
                    'ß' -> append("ss")
                    'æ' -> append("ae")
                    'œ' -> append("oe")
                    else -> append(c)
                }
            }
        }

        fun words(text: String): List<String> = normalize(text).split(SEPARATORS).filter { it.isNotEmpty() }
    }
}

/**
 * Whether the Home row shows (SPEC §3.5.6): the home clock is on, a home zone is known and its
 * clock differs from the local one right now. A neighboring zone on the same time (Warsaw seen
 * from Berlin) is not "away": the row would only repeat the local time.
 */
fun isAway(homeClock: Boolean, home: ZoneId?, local: ZoneId, at: Instant): Boolean =
    homeClock && home != null && home.rules.getOffset(at) != local.rules.getOffset(at)

/**
 * The stored order after the visible cities were reordered: cities hidden meanwhile (the Home row's
 * and the local zone's, while away) keep their places, the visible ones take theirs in the new order.
 */
fun mergeOrder(stored: List<String>, visibleInNewOrder: List<String>): List<String> {
    val visible = visibleInNewOrder.toSet()
    val next = visibleInNewOrder.iterator()
    return stored.map { if (it in visible && next.hasNext()) next.next() else it }
}

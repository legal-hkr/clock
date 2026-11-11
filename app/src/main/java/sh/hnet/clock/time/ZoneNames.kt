package sh.hnet.clock.time

import android.icu.text.Collator
import android.icu.text.TimeZoneFormat
import android.icu.text.TimeZoneNames
import android.icu.text.TimeZoneNames.NameType
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Cities and their names from the system's own time zone data (ICU), in one language: no network,
 * no list bundled with the app (SPEC §3.5.9). The system knows one representative city per zone.
 */
class ZoneNames(private val locale: Locale) {
    private val uLocale = ULocale.forLocale(locale)
    private val names = TimeZoneNames.getInstance(uLocale)

    /**
     * Abbreviations such as CET or EST are written the same in every language, but ICU only has
     * them in the locales that use them: British English knows the European ones, American English
     * the American ones. Most zones have none anywhere and go by their offset ("GMT+9").
     */
    private val abbreviations = listOf(TimeZoneNames.getInstance(ULocale.UK), TimeZoneNames.getInstance(ULocale.US))
    private val offsets = TimeZoneFormat.getInstance(uLocale)

    /** Every city the system has a zone for, A to Z in this language. Slow (hundreds of lookups): call off the main thread. */
    val all: List<City> by lazy {
        val now = Instant.now()
        val collator = Collator.getInstance(uLocale)
        TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, null, null)
            .mapNotNull { id -> zoneOrNull(id)?.let { city(id, now) } }
            .sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    /**
     * The id the system's list knows a zone under, so two spellings of one zone compare equal. ICU
     * keeps the older names ("Asia/Kolkata" is "Asia/Calcutta", "Europe/Kyiv" is "Europe/Kiev");
     * java.time reads both. The id itself when unknown.
     */
    fun canonical(zoneId: String): String = TimeZone.getCanonicalID(zoneId) ?: zoneId

    /** The city of [zoneId] with its names as of [at]; a zone without a city (UTC) goes by its id. */
    fun city(zoneId: String, at: Instant = Instant.now()): City {
        val id = canonical(zoneId)
        val name = names.getExemplarLocationName(id)?.takeIf { it.isNotBlank() } ?: id.substringAfterLast('/').replace('_', ' ')
        val region = TimeZone.getRegion(id)
        // "001" is the world: zones that belong to no country
        val country = if (region.isNullOrEmpty() || region == "001") "" else Locale.Builder().setRegion(region).build().getDisplayCountry(locale)
        val zone = zoneOrNull(id)
        val daylight = zone?.rules?.isDaylightSavings(at) == true
        val millis = at.toEpochMilli()
        val current = shortName(id, if (daylight) NameType.SHORT_DAYLIGHT else NameType.SHORT_STANDARD, millis)
        val other = shortName(id, if (daylight) NameType.SHORT_STANDARD else NameType.SHORT_DAYLIGHT, millis)
        val offset = offsets.formatOffsetShortLocalizedGMT((zone?.rules?.getOffset(at)?.totalSeconds ?: 0) * 1000)
        return City(id, name, country, current ?: offset, listOfNotNull(other, offset.takeIf { current != null }))
    }

    private fun shortName(id: String, type: NameType, at: Long): String? =
        (sequenceOf(names) + abbreviations).firstNotNullOfOrNull { source -> source.getDisplayName(id, type, at)?.takeIf { it.isNotBlank() } }

    companion object {
        /** The phone's current zone. */
        fun systemZone(): ZoneId = ZoneId.systemDefault()
    }
}

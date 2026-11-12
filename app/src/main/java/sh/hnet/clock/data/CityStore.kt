package sh.hnet.clock.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import sh.hnet.clock.widget.Widgets

private val Context.cities by preferencesDataStore("cities")

/**
 * The user's cities on the Clock destination: zone ids in the user's order. Data, not settings –
 * kept in a store of its own so Restore defaults leaves it alone; part of backups.
 */
class CityStore(private val context: Context) {
    private val key = stringPreferencesKey("zone_ids")

    val zoneIds: Flow<List<String>> = context.cities.data.map { prefs -> decode(prefs[key]) }

    suspend fun current(): List<String> = zoneIds.first()

    /** Adds [zoneId] at the end; a city already there stays where it is. */
    suspend fun add(zoneId: String) = update { if (zoneId in it) it else it + zoneId }

    /** Removes [zoneId]; its position, for Undo, or -1 when it wasn't there. */
    suspend fun remove(zoneId: String): Int {
        var position = -1
        update { list ->
            position = list.indexOf(zoneId)
            list - zoneId
        }
        return position
    }

    /** Puts [zoneId] back at [position] (Undo). */
    suspend fun insert(zoneId: String, position: Int) = update { list ->
        if (zoneId in list) list else list.toMutableList().apply { add(position.coerceIn(0, size), zoneId) }
    }

    /** The whole list in a new order, or another list altogether (restore). */
    suspend fun replaceAll(zoneIds: List<String>) = update { zoneIds.distinct() }

    private suspend fun update(change: (List<String>) -> List<String>) {
        context.cities.edit { prefs -> prefs[key] = change(decode(prefs[key])).joinToString("\n") }
        Widgets.world(context)
    }

    private fun decode(stored: String?): List<String> = stored?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}

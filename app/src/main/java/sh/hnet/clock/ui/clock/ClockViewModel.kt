package sh.hnet.clock.ui.clock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.clock.ClockApplication

/** The Clock destination's data: the user's cities and the settings that shape the page. Activity-scoped. */
class ClockViewModel(private val app: ClockApplication) : ViewModel() {
    /** Zone ids in the user's order; null until read. */
    val cities: StateFlow<List<String>?> = app.cities.zoneIds.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val showSeconds: StateFlow<Boolean> = app.preferences.showSeconds.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val homeClock: StateFlow<Boolean> = app.preferences.homeClock.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val homeZone: StateFlow<String?> = app.preferences.homeZone.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The city added last, which the list scrolls to once (SPEC §3.5.7); taken by the page. */
    var justAdded: String? = null

    fun add(zoneId: String) {
        justAdded = zoneId
        viewModelScope.launch { app.cities.add(zoneId) }
    }

    fun setOrder(zoneIds: List<String>) {
        viewModelScope.launch { app.cities.replaceAll(zoneIds) }
    }

    /** Removes a city at once; its position, for [restore]. */
    suspend fun remove(zoneId: String): Int = app.cities.remove(zoneId)

    suspend fun restore(zoneId: String, position: Int) = app.cities.insert(zoneId, position)

    fun setShowSeconds(on: Boolean) = viewModelScope.launch { app.preferences.setShowSeconds(on) }
    fun setHomeClock(on: Boolean) = viewModelScope.launch { app.preferences.setHomeClock(on) }
    fun setHomeZone(zoneId: String) = viewModelScope.launch { app.preferences.setHomeZone(zoneId) }
}

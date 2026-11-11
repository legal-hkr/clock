package sh.hnet.clock

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.hnet.clock.backup.BackupManager
import sh.hnet.clock.data.CityStore
import sh.hnet.clock.stopwatch.StopwatchController
import sh.hnet.clock.time.ZoneNames
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ClockApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    val cities: CityStore by lazy { CityStore(this) }

    val stopwatch: StopwatchController by lazy { StopwatchController(this) }

    private val zoneNames = ConcurrentHashMap<String, ZoneNames>()

    /** The system's cities and zone names in [locale], kept for the process (the full list is built once per language). */
    fun zoneNames(locale: Locale): ZoneNames = zoneNames.getOrPut(locale.toLanguageTag()) { ZoneNames(locale) }

    override fun onCreate() {
        super.onCreate()
        // Home is where the phone is when the app first runs (SPEC §3.5.6)
        appScope.launch { preferences.ensureHomeZone(ZoneId.systemDefault().id) }
        // A notification the user dismissed, or lost with the process, comes back with the app
        stopwatch.sync()
    }

    val backup: BackupManager by lazy { BackupManager(this, getSharedPreferences("backup", MODE_PRIVATE)) }
}

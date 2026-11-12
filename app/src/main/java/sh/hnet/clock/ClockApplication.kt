package sh.hnet.clock

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import android.os.UserManager
import kotlinx.coroutines.launch
import sh.hnet.clock.widget.Widgets
import sh.hnet.clock.alarm.AlarmController
import sh.hnet.clock.backup.BackupManager
import sh.hnet.clock.data.CityStore
import sh.hnet.clock.stopwatch.StopwatchController
import sh.hnet.clock.timer.TimerController
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

    val timers: TimerController by lazy { TimerController(this) }

    private val zoneNames = ConcurrentHashMap<String, ZoneNames>()

    /** The system's cities and zone names in [locale], kept for the process (the full list is built once per language). */
    fun zoneNames(locale: Locale): ZoneNames = zoneNames.getOrPut(locale.toLanguageTag()) { ZoneNames(locale) }

    /** The alarms; the one part of the app that works before the phone is first unlocked after a restart. */
    val alarms: AlarmController by lazy { AlarmController(this) }

    /** False after a restart of the phone until it is first unlocked: everything but [alarms] is locked away until then. */
    val unlocked: Boolean get() = getSystemService(UserManager::class.java).isUserUnlocked

    private var started = false

    override fun onCreate() {
        super.onCreate()
        // What is due is dealt with whenever the process comes up, whatever brought it up
        alarms.refresh()
        if (unlocked) afterUnlock()
    }

    /** The rest of the start, once the app's own storage can be read; also called by the boot receiver. */
    @Synchronized
    fun afterUnlock() {
        if (started || !unlocked) return
        started = true
        // Home is where the phone is when the app first runs (SPEC §3.5.6)
        appScope.launch { preferences.ensureHomeZone(ZoneId.systemDefault().id) }
        // A notification the user dismissed, or lost with the process, comes back with the app
        stopwatch.sync()
        timers.silenceOverdue()
        timers.refresh()
        Widgets.all(this)
    }

    val backup: BackupManager by lazy { BackupManager(this, getSharedPreferences("backup", MODE_PRIVATE)) }
}

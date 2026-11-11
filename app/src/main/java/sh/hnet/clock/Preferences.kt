package sh.hnet.clock

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import sh.hnet.clock.backup.BackupSettings
import sh.hnet.clock.ring.SoundChoice
import sh.hnet.clock.time.zoneOrNull
import sh.hnet.clock.ui.Tab

private val Context.settings by preferencesDataStore("settings")

/** Gradually increase volume: Off, 15 s, 30 s, 1 min, 2 min, 5 min (SPEC §3.9.1). */
val GRADUAL_CHOICES = listOf(0, 15, 30, 60, 120, 300)

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). */
class Preferences(private val context: Context) {
    private val lastDestinationKey = stringPreferencesKey("last_destination")

    /** The destination used last, which the app opens on (SPEC §3.1.4); Alarms before any. A memory, not a setting. */
    val lastDestination: Flow<Tab> = context.settings.data.map { prefs ->
        prefs[lastDestinationKey]?.let { name -> Tab.entries.firstOrNull { it.name == name } } ?: Tab.ALARMS
    }

    suspend fun setLastDestination(tab: Tab) = context.settings.edit { it[lastDestinationKey] = tab.name }

    private val showSecondsKey = booleanPreferencesKey("show_seconds")

    /** Seconds after the local time on the Clock destination (SPEC §3.9.3); off by default. */
    val showSeconds: Flow<Boolean> = context.settings.data.map { it[showSecondsKey] ?: false }

    suspend fun setShowSeconds(on: Boolean) = context.settings.edit { it[showSecondsKey] = on }

    private val homeClockKey = booleanPreferencesKey("home_clock")

    /** The Home row while the phone is on another time than home (SPEC §3.5.6); on by default. */
    val homeClock: Flow<Boolean> = context.settings.data.map { it[homeClockKey] ?: true }

    suspend fun setHomeClock(on: Boolean) = context.settings.edit { it[homeClockKey] = on }

    private val timerSoundKey = stringPreferencesKey("timer_sound")
    private val timerSoundTitleKey = stringPreferencesKey("timer_sound_title")

    /** What an ended timer plays (SPEC §3.9.2); the phone's default alarm sound unless chosen. */
    val timerSound: Flow<SoundChoice> = context.settings.data.map { SoundChoice.decode(it[timerSoundKey], it[timerSoundTitleKey]) }

    suspend fun setTimerSound(sound: SoundChoice) = context.settings.edit { prefs ->
        prefs[timerSoundKey] = sound.encode()
        if (sound is SoundChoice.Sound) prefs[timerSoundTitleKey] = sound.title else prefs.remove(timerSoundTitleKey)
    }

    private val timerVibrationKey = booleanPreferencesKey("timer_vibration")

    /** Vibration with an ended timer; on by default. A silent timer vibrates whatever this says (§3.3.6). */
    val timerVibration: Flow<Boolean> = context.settings.data.map { it[timerVibrationKey] ?: true }

    suspend fun setTimerVibration(on: Boolean) = context.settings.edit { it[timerVibrationKey] = on }

    private val timerGradualKey = intPreferencesKey("timer_gradual_seconds")

    /** Seconds over which an ended timer's sound rises; 0 (off) by default. */
    val timerGradual: Flow<Int> = context.settings.data.map { prefs -> (prefs[timerGradualKey] ?: 0).takeIf { it in GRADUAL_CHOICES } ?: 0 }

    suspend fun setTimerGradual(seconds: Int) = context.settings.edit { it[timerGradualKey] = seconds }

    private val homeZoneKey = stringPreferencesKey("home_zone")

    /** The home time zone's id; null until [ensureHomeZone] has noted one. */
    val homeZone: Flow<String?> = context.settings.data.map { it[homeZoneKey] }

    suspend fun setHomeZone(zoneId: String) = context.settings.edit { it[homeZoneKey] = zoneId }

    /**
     * Home is where the phone was when the app first ran (and again after Restore defaults or a
     * restore that names no home): notes [systemZoneId] unless a home zone is already known.
     */
    suspend fun ensureHomeZone(systemZoneId: String) = context.settings.edit { prefs ->
        if (prefs[homeZoneKey] == null) prefs[homeZoneKey] = systemZoneId
    }

    /**
     * Restore defaults (Settings › Reset): every setting of this store back to its default. The last
     * used destination is a memory, not a setting, and stays. Home becomes the phone's current zone.
     */
    suspend fun restoreDefaults(systemZoneId: String) = context.settings.edit { prefs ->
        val last = prefs[lastDestinationKey]
        prefs.clear()
        last?.let { prefs[lastDestinationKey] = it }
        prefs[homeZoneKey] = systemZoneId
    }

    /** The settings as a backup holds them. */
    suspend fun backupSettings(): BackupSettings {
        val prefs = context.settings.data.first()
        return BackupSettings(
            clock = BackupSettings.Clock(
                showSeconds = prefs[showSecondsKey] ?: false,
                homeClock = prefs[homeClockKey] ?: true,
                homeZone = prefs[homeZoneKey],
            ),
            timers = BackupSettings.Timers(
                sound = prefs[timerSoundKey],
                soundTitle = prefs[timerSoundTitleKey],
                vibration = prefs[timerVibrationKey] ?: true,
                gradualSeconds = prefs[timerGradualKey] ?: 0,
            ),
        )
    }

    /**
     * Replaces every setting by the backup's; what the backup doesn't name returns to its default.
     * A home zone this phone doesn't know is left out, and home is then the phone's current zone.
     */
    suspend fun restoreSettings(backup: BackupSettings, systemZoneId: String) = context.settings.edit { prefs ->
        val last = prefs[lastDestinationKey]
        prefs.clear()
        last?.let { prefs[lastDestinationKey] = it }
        backup.clock?.showSeconds?.let { prefs[showSecondsKey] = it }
        backup.clock?.homeClock?.let { prefs[homeClockKey] = it }
        backup.timers?.sound?.let { prefs[timerSoundKey] = it }
        backup.timers?.soundTitle?.let { prefs[timerSoundTitleKey] = it }
        backup.timers?.vibration?.let { prefs[timerVibrationKey] = it }
        backup.timers?.gradualSeconds?.takeIf { it in GRADUAL_CHOICES }?.let { prefs[timerGradualKey] = it }
        prefs[homeZoneKey] = backup.clock?.homeZone?.takeIf { zoneOrNull(it) != null } ?: systemZoneId
    }
}

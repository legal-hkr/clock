package sh.hnet.clock

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import sh.hnet.clock.backup.BackupSettings
import sh.hnet.clock.ui.Tab

private val Context.settings by preferencesDataStore("settings")

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). */
class Preferences(private val context: Context) {
    private val lastDestinationKey = stringPreferencesKey("last_destination")

    /** The destination used last, which the app opens on (SPEC §3.1.4); Alarms before any. A memory, not a setting. */
    val lastDestination: Flow<Tab> = context.settings.data.map { prefs ->
        prefs[lastDestinationKey]?.let { name -> Tab.entries.firstOrNull { it.name == name } } ?: Tab.ALARMS
    }

    suspend fun setLastDestination(tab: Tab) = context.settings.edit { it[lastDestinationKey] = tab.name }

    /**
     * Restore defaults (Settings › Reset): every setting of this store back to its default. The last
     * used destination is a memory, not a setting, and stays.
     */
    suspend fun restoreDefaults() = context.settings.edit { prefs ->
        val last = prefs[lastDestinationKey]
        prefs.clear()
        last?.let { prefs[lastDestinationKey] = it }
    }

    /** The settings as a backup holds them. No setting exists yet: each stage adds its own. */
    suspend fun backupSettings(): BackupSettings {
        context.settings.data.first()
        return BackupSettings()
    }

    /** Replaces every setting by the backup's; what the backup doesn't name returns to its default. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun restoreSettings(backup: BackupSettings) = restoreDefaults()
}

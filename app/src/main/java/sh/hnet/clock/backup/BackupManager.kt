package sh.hnet.clock.backup

import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import sh.hnet.clock.BuildConfig
import sh.hnet.clock.ClockApplication
import java.io.IOException
import sh.hnet.clock.GRADUAL_CHOICES
import sh.hnet.clock.alarm.Alarm
import sh.hnet.clock.alarm.AlarmSettings
import sh.hnet.clock.alarm.VolumeButtons
import sh.hnet.clock.time.zoneOrNull
import sh.hnet.clock.timer.Timer
import sh.hnet.clock.timer.TimerController
import java.time.Instant
import java.time.ZoneId

/**
 * Backup and restore (SPEC §3.9.5, as Music's): writes what a backup holds to a file the user
 * picked, and reads one back. A restore replaces all of it. Nothing is logged.
 */
class BackupManager(private val app: ClockApplication, private val stamps: SharedPreferences) {
    private val resolver = app.contentResolver
    private val _lastBackup = MutableStateFlow(stamps.getLong(LAST_BACKUP, 0L).takeIf { it > 0 })

    /** When the last backup was saved on this phone; null before the first. Not a setting. */
    val lastBackup: StateFlow<Long?> = _lastBackup.asStateFlow()

    sealed interface Created {
        data class Saved(val fileName: String) : Created
        data class Failed(val fileName: String, val details: String) : Created
    }

    /** A picked file: checked ([check]), or not readable at all ([check] null, with [details]). */
    data class Picked(val fileName: String, val check: BackupCheck?, val details: String? = null)

    suspend fun create(uri: Uri): Created = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val text = Backup.encode(snapshot())
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("No output")).use { it.write(text.toByteArray()) }
            val now = System.currentTimeMillis()
            stamps.edit { putLong(LAST_BACKUP, now) }
            _lastBackup.value = now
            Created.Saved(fileName)
        } catch (e: IOException) {
            Created.Failed(fileName, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Created.Failed(fileName, e.javaClass.simpleName)
        }
    }

    /** Reads the picked file once and checks it; nothing changes yet. */
    suspend fun read(uri: Uri): Picked = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val bytes = (resolver.openInputStream(uri) ?: throw IOException("No input")).use { input ->
                val read = input.readNBytes(MAX_BYTES + 1)
                if (read.size > MAX_BYTES) return@withContext Picked(fileName, BackupCheck.NotOurs("Larger than ${MAX_BYTES / 1_048_576} MB"))
                read
            }
            Picked(fileName, Backup.check(bytes.toString(Charsets.UTF_8)))
        } catch (e: IOException) {
            Picked(fileName, null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Picked(fileName, null, e.javaClass.simpleName)
        }
    }

    /** Replaces everything a backup holds by the file's. */
    suspend fun restore(backup: BackupFile) = withContext(Dispatchers.IO) {
        app.preferences.restoreSettings(backup.settings, ZoneId.systemDefault().id)
        // Cities this phone has no zone for are left out; a backup from before cities says nothing about them
        withContext(Dispatchers.Main) {
            // Alarm settings live with the alarms (readable before the first unlock), not in the settings store
            val stored = backup.settings.alarms
            val defaults = AlarmSettings()
            app.alarms.setSettings {
                AlarmSettings(
                    snoozeMinutes = stored?.snoozeMinutes?.takeIf { it in AlarmSettings.SNOOZE_CHOICES } ?: defaults.snoozeMinutes,
                    silenceAfterMinutes = stored?.silenceAfterMinutes?.takeIf { it in AlarmSettings.SILENCE_CHOICES } ?: defaults.silenceAfterMinutes,
                    gradualSeconds = stored?.gradualSeconds?.takeIf { it in GRADUAL_CHOICES } ?: defaults.gradualSeconds,
                    volumeButtons = VolumeButtons.entries.firstOrNull { it.name == stored?.volumeButtons } ?: defaults.volumeButtons,
                    sound = stored?.sound ?: defaults.sound,
                    soundTitle = stored?.soundTitle,
                    vibration = stored?.vibration ?: defaults.vibration,
                    upcomingMinutes = stored?.upcomingMinutes?.takeIf { it in AlarmSettings.UPCOMING_CHOICES } ?: defaults.upcomingMinutes,
                )
            }
            backup.alarms?.let { alarms ->
                app.alarms.replaceAll(
                    alarms.filter { it.hour in 0..23 && it.minute in 0..59 }.map { stored ->
                        Alarm(
                            id = 0, hour = stored.hour, minute = stored.minute, days = stored.days.filter { it in 1..7 }.toSet(), date = stored.date,
                            label = stored.label.trim().take(Alarm.MAX_LABEL), sound = stored.sound ?: "default", soundTitle = stored.soundTitle,
                            vibration = stored.vibration, snooze = stored.snooze, enabled = stored.enabled,
                        )
                    },
                )
            }
        }
        backup.cities?.let { ids -> app.cities.replaceAll(ids.filter { zoneOrNull(it) != null }) }
        backup.timerPresets?.let { presets ->
            val usable = presets.filter { it.name.isNotBlank() && it.seconds in 1..Timer.MAX / 1000 }
            withContext(Dispatchers.Main) {
                app.timers.replacePresets(usable.map { it.name.trim().take(TimerController.MAX_PRESET_NAME) to it.seconds * 1000 })
            }
        }
    }

    private suspend fun snapshot() = BackupFile(
        appVersion = BuildConfig.VERSION_NAME.removePrefix("v"),
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        createdAt = Instant.now().toString(),
        settings = app.preferences.backupSettings().copy(
            alarms = app.alarms.settings.value.let {
                BackupSettings.Alarms(it.snoozeMinutes, it.silenceAfterMinutes, it.gradualSeconds, it.volumeButtons.name, it.sound, it.soundTitle, it.vibration, it.upcomingMinutes)
            },
        ),
        alarms = app.alarms.alarms.value.map {
            BackupAlarm(it.hour, it.minute, it.days.sorted(), it.date, it.label, it.sound, it.soundTitle, it.vibration, it.snooze, it.enabled)
        },
        cities = app.cities.current(),
        timerPresets = app.timers.presets.value.map { BackupPreset(it.name, it.duration / 1000) },
    )

    fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val LAST_BACKUP = "last_backup"
        /** Far above any real backup. */
        const val MAX_BYTES = 4 * 1_048_576
    }
}

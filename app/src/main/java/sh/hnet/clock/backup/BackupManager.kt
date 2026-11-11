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
import sh.hnet.clock.time.zoneOrNull
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
        backup.cities?.let { ids -> app.cities.replaceAll(ids.filter { zoneOrNull(it) != null }) }
    }

    private suspend fun snapshot() = BackupFile(
        appVersion = BuildConfig.VERSION_NAME.removePrefix("v"),
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        createdAt = Instant.now().toString(),
        settings = app.preferences.backupSettings(),
        cities = app.cities.current(),
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

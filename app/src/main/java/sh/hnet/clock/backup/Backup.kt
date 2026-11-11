package sh.hnet.clock.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Clock's backup file (SPEC §3.9.5), in the same shape as Music's: for now the settings alone;
 * alarms, cities and timer presets join it with their stages, as optional fields of format
 * version 1. Never in it: running timers, the stopwatch, skip and snooze state, the app language
 * and the last used destination. Pure Kotlin, so the format is unit-tested.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val appVersionCode: Long,
    /** ISO 8601, UTC. */
    val createdAt: String,
    val settings: BackupSettings = BackupSettings(),
) {
    companion object {
        const val FORMAT = "sh.hnet.clock.backup"
        const val FORMAT_VERSION = 1
    }
}

/**
 * The settings. Every field is optional and enum values go by name: a field absent from the file,
 * or a name from a later version, falls back to the default on restore. Empty until the first
 * setting exists.
 */
@Serializable
class BackupSettings {
    override fun equals(other: Any?) = other is BackupSettings
    override fun hashCode() = 0
}

/** What a picked file turned out to be, before anything changes. */
sealed interface BackupCheck {
    data class Valid(val backup: BackupFile) : BackupCheck

    /** Not JSON, or JSON of something else. */
    data class NotOurs(val details: String) : BackupCheck

    /** Made by a later version of Clock with a format this one doesn't know. */
    data class Newer(val formatVersion: Int) : BackupCheck
}

object Backup {
    /** Lenient on what it reads (unknown fields from later versions within the format), plain on what it writes. */
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** Checks [text] in order: JSON at all, our format, a version we read, then the content. */
    fun check(text: String): BackupCheck {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return BackupCheck.NotOurs(e.message ?: "Not JSON")
        } catch (e: IllegalArgumentException) {
            return BackupCheck.NotOurs(e.message ?: "Not a JSON object")
        }
        if (root.string("format") != BackupFile.FORMAT) return BackupCheck.NotOurs("format: ${root.string("format") ?: "absent"}")
        val version = root["formatVersion"]?.jsonPrimitive?.intOrNull ?: return BackupCheck.NotOurs("formatVersion absent")
        if (version > BackupFile.FORMAT_VERSION) return BackupCheck.Newer(version)
        return try {
            BackupCheck.Valid(json.decodeFromJsonElement(BackupFile.serializer(), root))
        } catch (e: SerializationException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        } catch (e: IllegalArgumentException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        }
    }

    private fun JsonObject.string(key: String): String? = try {
        this[key]?.jsonPrimitive?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    }
}

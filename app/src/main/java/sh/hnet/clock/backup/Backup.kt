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
 * Clock's backup file (SPEC §3.9.5), in the same shape as Music's: the settings, the alarms, the
 * cities and the timer presets, each an optional field of format version 1. Never in it: running timers, the stopwatch, skip and snooze state, the app language
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
    /** Zone ids of the Clock destination's cities, in the user's order; absent in backups from before cities, which then stay as they are. */
    val cities: List<String>? = null,
    /** The timer presets in the user's order; absent in backups from before timers, which then stay as they are. */
    val timerPresets: List<BackupPreset>? = null,
    /** The alarms; absent in backups from before alarms, which then stay as they are. */
    val alarms: List<BackupAlarm>? = null,
) {
    companion object {
        const val FORMAT = "sh.hnet.clock.backup"
        const val FORMAT_VERSION = 1
    }
}

/**
 * The settings. Every field is optional and enum values go by name: a field absent from the file,
 * or a name from a later version, falls back to the default on restore.
 */
@Serializable
data class BackupSettings(val clock: Clock? = null, val timers: Timers? = null, val alarms: Alarms? = null) {
    /** Settings › Clock. */
    @Serializable
    data class Clock(val showSeconds: Boolean? = null, val homeClock: Boolean? = null, val homeZone: String? = null)

    /** Settings › Alarms; the sound of new alarms as [Timers.sound]. */
    @Serializable
    data class Alarms(
        val snoozeMinutes: Int? = null,
        val silenceAfterMinutes: Int? = null,
        val gradualSeconds: Int? = null,
        val volumeButtons: String? = null,
        val sound: String? = null,
        val soundTitle: String? = null,
        val vibration: Boolean? = null,
        val upcomingMinutes: Int? = null,
    )

    /**
     * Settings › Timers. [sound] is "default", "silent" or a sound's address on the phone it was
     * chosen on, with its [soundTitle]: on a phone without that sound the timer rings with the
     * phone's default (§3.9.5).
     */
    @Serializable
    data class Timers(val sound: String? = null, val soundTitle: String? = null, val vibration: Boolean? = null, val gradualSeconds: Int? = null)
}

/**
 * An alarm as set: its time, its days (ISO numbers, 1 = Monday) or its date, and how it rings.
 * Nothing of the moment – skipped, snoozed, ringing – is kept (§3.9.5).
 */
@Serializable
data class BackupAlarm(
    val hour: Int,
    val minute: Int,
    val days: List<Int> = emptyList(),
    val date: String? = null,
    val label: String = "",
    val sound: String? = null,
    val soundTitle: String? = null,
    val vibration: Boolean = true,
    val snooze: Boolean = true,
    val enabled: Boolean = true,
)

/** A timer preset: its name and its duration in seconds. */
@Serializable
data class BackupPreset(val name: String, val seconds: Long)

/** What a picked file turned out to be, before anything changes. */
sealed interface BackupCheck {
    data class Valid(val backup: BackupFile) : BackupCheck {
        val cities: Int get() = backup.cities.orEmpty().size
        val presets: Int get() = backup.timerPresets.orEmpty().size
        val alarms: Int get() = backup.alarms.orEmpty().size
    }

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

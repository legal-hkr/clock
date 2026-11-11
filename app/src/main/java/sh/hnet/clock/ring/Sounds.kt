package sh.hnet.clock.ring

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri

/**
 * What rings (SPEC §2.7): the phone's default alarm sound (followed, whatever it is), a chosen one
 * of the system's alarm sounds, or nothing – "Silent", vibration only. Stored as one string, with
 * the sound's title beside it so a sound that is gone can still be named (§3.3.5).
 */
sealed interface SoundChoice {
    data object Default : SoundChoice
    data object Silent : SoundChoice
    data class Sound(val uri: String, val title: String) : SoundChoice

    fun encode(): String = when (this) {
        Default -> DEFAULT
        Silent -> SILENT
        is Sound -> uri
    }

    companion object {
        private const val DEFAULT = "default"
        private const val SILENT = "silent"

        fun decode(stored: String?, title: String?): SoundChoice = when (stored) {
            null, "", DEFAULT -> Default
            SILENT -> Silent
            else -> Sound(stored, title.orEmpty())
        }
    }
}

/** One of the system's alarm sounds. */
data class SystemSound(val uri: String, val title: String)

/** The system's alarm sounds: no files of the user's, no sounds of the app's own (owner's decision). */
object Sounds {
    /** Every alarm sound the phone has, A to Z by title. Reads the system's media index: call off the main thread. */
    fun list(context: Context): List<SystemSound> {
        val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_ALARM) }
        val found = ArrayList<SystemSound>()
        try {
            val cursor = manager.cursor
            while (cursor.moveToNext()) {
                val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: continue
                found += SystemSound(manager.getRingtoneUri(cursor.position).toString(), title)
            }
        } catch (_: RuntimeException) {
            // A broken media index: the list is what could be read
        }
        return found.distinctBy { it.uri }.sortedBy { it.title.lowercase() }
    }

    /**
     * Where a stored sound is on this phone (SPEC §3.9.5: sounds are kept by name). A backup made
     * on another phone carries that phone's addresses; the sound of the same name here takes its
     * place. Without one the address stays as it is, and the sound is reported as gone.
     */
    fun here(sound: String?, title: String?, sounds: List<SystemSound>): String? {
        if (SoundChoice.decode(sound, title) !is SoundChoice.Sound || title.isNullOrEmpty()) return sound
        return sounds.firstOrNull { it.uri == sound && it.title == title }?.uri ?: sounds.firstOrNull { it.title == title }?.uri ?: sound
    }

    /**
     * Whether [choice] is a sound this phone no longer has (§3.3.5): its address is unknown, or
     * belongs to a sound of another name. [sounds] maps addresses to titles; with null – the list
     * not read yet, or unreadable – nothing is called gone.
     */
    fun gone(choice: SoundChoice, sounds: Map<String, String>?): Boolean {
        if (choice !is SoundChoice.Sound || sounds == null) return false
        val title = sounds[choice.uri] ?: return true
        return choice.title.isNotEmpty() && title != choice.title
    }

    /** The phone's default alarm sound; null on a phone that has none set. */
    fun defaultUri(context: Context): Uri? =
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

    /** The default sound's name, for "Phone’s default (Argon)". */
    fun defaultTitle(context: Context): String? = title(context, defaultUri(context))

    fun title(context: Context, uri: Uri?): String? = try {
        uri?.let { RingtoneManager.getRingtone(context, it)?.getTitle(context) }
    } catch (_: RuntimeException) {
        null
    }

    /**
     * What to play for [choice], in the order the player tries it: nothing for Silent; else the
     * chosen sound, the phone's default, and last the default by its address in the system's
     * settings – the system keeps a copy of that one readable before the phone is first unlocked
     * after a restart, when the media library is still locked.
     */
    fun uris(context: Context, choice: SoundChoice): List<Uri> = when (choice) {
        SoundChoice.Silent -> emptyList()
        SoundChoice.Default -> listOfNotNull(defaultUri(context), Settings.System.DEFAULT_ALARM_ALERT_URI)
        is SoundChoice.Sound -> listOfNotNull(choice.uri.toUri(), defaultUri(context), Settings.System.DEFAULT_ALARM_ALERT_URI)
    }
}

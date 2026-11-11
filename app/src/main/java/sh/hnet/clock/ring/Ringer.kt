package sh.hnet.clock.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import java.io.IOException

/**
 * Plays what rings: a system alarm sound, looping, on the alarm stream at the phone's alarm volume
 * (SPEC §3.8.3), optionally rising from quiet over a while (§3.4.10), and the vibration, which
 * starts at once. A sound that can't be played falls back to the next one given (the phone's
 * default); with none playable the vibration is all there is. Main thread only.
 */
class Ringer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build()
    private var player: MediaPlayer? = null
    private var ramp: Runnable? = null

    var playing = false
        private set

    /**
     * @param sounds What to play, in order of preference; empty for silence
     * @param gradualSeconds Seconds over which the volume rises from its lowest; 0 plays at full volume at once
     * @param wake Keeps the processor awake while playing (ringing with the screen off); not for a preview
     */
    fun start(sounds: List<Uri>, vibrate: Boolean, gradualSeconds: Int = 0, wake: Boolean = true) {
        stop()
        playing = true
        if (vibrate) {
            vibrator?.vibrate(VibrationEffect.createWaveform(PATTERN, 0), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        }
        val media = sounds.firstNotNullOfOrNull { open(it, wake) } ?: return
        player = media
        audio.requestAudioFocus(focus)
        if (gradualSeconds > 0) {
            val started = SystemClock.elapsedRealtime()
            val length = gradualSeconds * 1000f
            val step = object : Runnable {
                override fun run() {
                    val share = ((SystemClock.elapsedRealtime() - started) / length).coerceIn(0f, 1f)
                    val volume = LOWEST + (1f - LOWEST) * share
                    try {
                        media.setVolume(volume, volume)
                    } catch (_: IllegalStateException) {
                        return
                    }
                    if (share < 1f) handler.postDelayed(this, RAMP_STEP)
                }
            }
            ramp = step
            step.run()
        }
        media.start()
    }

    /** One short pulse: what choosing "Silent" sounds like (§3.3.3). */
    fun pulse() {
        vibrator?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    }

    fun stop() {
        playing = false
        ramp?.let(handler::removeCallbacks)
        ramp = null
        vibrator?.cancel()
        player?.let { media ->
            try {
                media.stop()
            } catch (_: IllegalStateException) {
            }
            media.release()
            audio.abandonAudioFocusRequest(focus)
        }
        player = null
    }

    private fun open(uri: Uri, wake: Boolean): MediaPlayer? {
        val media = MediaPlayer()
        return try {
            media.setAudioAttributes(attributes)
            media.setDataSource(context, uri)
            media.isLooping = true
            if (wake) media.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            // System sounds are short local files: prepared at once
            media.prepare()
            media
        } catch (_: IOException) {
            media.release()
            null
        } catch (_: RuntimeException) {
            // A sound that is gone, or one the phone won't let us read
            media.release()
            null
        }
    }

    private companion object {
        val PATTERN = longArrayOf(0, 600, 400)
        const val LOWEST = 0.05f
        const val RAMP_STEP = 250L
    }
}

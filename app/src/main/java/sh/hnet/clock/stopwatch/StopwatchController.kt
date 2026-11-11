package sh.hnet.clock.stopwatch

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The present [Moment], as the stopwatch and the timers read it. */
object Moments {
    fun now(context: Context): Moment = Moment(
        wall = System.currentTimeMillis(),
        sinceBoot = android.os.SystemClock.elapsedRealtime(),
        boot = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0),
    )
}

/**
 * The one stopwatch: its state, kept on every change (so it survives the process and a restart of
 * the phone), and its notification. Used from the UI, the notification's buttons and the boot
 * receiver alike, so everything here is synchronous and cheap.
 */
class StopwatchController(private val context: Context) {
    private val store = context.getSharedPreferences("stopwatch", Context.MODE_PRIVATE)
    private val notification = StopwatchNotification(context)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Stopwatch> = _state.asStateFlow()

    fun now(): Moment = Moments.now(context)

    fun elapsed(): Long = _state.value.elapsed(now())

    fun start() = change { it.start(now()) }
    fun pause() = change { it.pause(now()) }
    fun lap() = change { it.lap(now()) }

    /** Back to zero at once; what it was, for [restore] (Undo). */
    fun reset(): Stopwatch {
        val before = _state.value
        change { Stopwatch() }
        return before
    }

    /** Puts back what [reset] cleared, unless the stopwatch was used again meanwhile. */
    fun restore(before: Stopwatch) = change { if (it.isZero) before else it }

    /** Shows the notification the state calls for: after a start of the process, a restart of the phone or a permission granted. */
    fun sync() = notification.show(_state.value, now())

    @Synchronized
    private fun change(transform: (Stopwatch) -> Stopwatch) {
        val next = transform(_state.value)
        if (next == _state.value) return
        store.edit { putString(KEY, json.encodeToString(Stopwatch.serializer(), next)) }
        _state.value = next
        notification.show(next, now())
    }

    private fun read(): Stopwatch = store.getString(KEY, null)?.let { text ->
        try {
            json.decodeFromString(Stopwatch.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    } ?: Stopwatch()

    private companion object {
        const val KEY = "state"
        val json = Json { ignoreUnknownKeys = true }
    }
}

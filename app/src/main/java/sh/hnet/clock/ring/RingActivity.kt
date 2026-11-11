@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ring

import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.alarm.Alarm
import sh.hnet.clock.alarm.VolumeButtons
import sh.hnet.clock.ui.components.rememberNow
import sh.hnet.clock.ui.components.rememberTimeFormats
import java.time.ZoneId
import sh.hnet.clock.stopwatch.Moment
import sh.hnet.clock.timer.Timer
import sh.hnet.clock.timer.TimerController
import sh.hnet.clock.timer.TimerFormat
import sh.hnet.clock.timer.shown
import sh.hnet.clock.ui.components.durationWords
import sh.hnet.clock.ui.components.groupShapes
import sh.hnet.clock.ui.components.tabular
import sh.hnet.clock.ui.theme.ClockTheme

/**
 * The ringing page (SPEC §3.4): full screen over the lock screen, screen on, following the phone's
 * orientation, with nothing on it but what rings and its answers. The big target is always the
 * action that makes it quiet without losing anything (§2.2): Snooze for an alarm, whose Stop takes
 * a drag; Stop for a timer, with Add 1 minute below. It closes when nothing rings any more.
 */
class RingActivity : ComponentActivity() {
    private val app: ClockApplication get() = application as ClockApplication
    private val timers: TimerController get() = app.timers

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ClockTheme {
                val alarms by app.alarms.alarms.collectAsStateWithLifecycle()
                val settings by app.alarms.settings.collectAsStateWithLifecycle()
                val alarm = alarms.filter { it.ringing != null }.minByOrNull { it.ringing ?: 0 }
                // Before the phone is first unlocked after a restart there are no timers to look at (SPEC §5.8)
                val unlocked = remember { app.unlocked }
                val ringing = if (unlocked) {
                    val all by timers.timers.collectAsStateWithLifecycle()
                    val now by produceState(timers.now()) {
                        while (true) {
                            value = timers.now()
                            delay(200)
                        }
                    }
                    all.filter { it.ringing(now) }.shown(now) to now
                } else null
                val nothing = alarm == null && ringing?.first.isNullOrEmpty()
                LaunchedEffect(nothing) { if (nothing) finish() }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                        val single = ringing?.first?.singleOrNull()
                        when {
                            // An alarm comes first; timers follow once it is answered (§3.4.8)
                            // A page of its own for each alarm: the next one starts with the thumb back at the start
                            alarm != null -> key(alarm.id) {
                                AlarmPage(alarm, settings.snoozeMinutes, onSnooze = { app.alarms.snooze(alarm.id) }, onStop = { app.alarms.stop(alarm.id) })
                            }
                            ringing == null || ringing.first.isEmpty() -> Unit
                            single != null -> OneTimer(single, ringing.second, onStop = { timers.delete(single.id) }, onAddMinute = { timers.addMinute(single.id) })
                            else -> SeveralTimers(ringing.first, ringing.second, timers)
                        }
                    }
                }
            }
        }
    }

    /**
     * The volume buttons (§3.4.9): for a ringing alarm what Settings › Alarms says – Snooze by
     * default, which an alarm without snooze ignores –; for ringing timers always Stop. The power
     * button is the system's and does nothing here.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyDown(keyCode, event)
        val alarm = app.alarms.ringing().firstOrNull()
        when {
            alarm == null -> if (app.unlocked) timers.stopRinging()
            else -> when (app.alarms.settings.value.volumeButtons) {
                VolumeButtons.SNOOZE -> app.alarms.snooze(alarm.id)
                VolumeButtons.STOP -> app.alarms.stop(alarm.id)
                VolumeButtons.NOTHING -> Unit
            }
        }
        return true
    }
}

/**
 * A ringing alarm (§3.4.2–4): its label and the time, nothing else. Snooze is the huge easy target
 * in the middle – a mistaken snooze costs nothing –, Stop the drag below it. An alarm with snooze
 * off shows the track alone; the cookie's place stays empty. On its side, label, time and track
 * take the start half and the cookie fills the end half.
 */
@Composable
private fun AlarmPage(alarm: Alarm, snoozeMinutes: Int, onSnooze: () -> Unit, onStop: () -> Unit) {
    val formats = rememberTimeFormats()
    val now by rememberNow(everySecond = false)
    val parts = formats.parts(now, ZoneId.systemDefault())
    val title: @Composable () -> Unit = {
        if (alarm.label.isNotEmpty()) {
            Text(alarm.label, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        }
        Row {
            if (parts.periodFirst) parts.period?.let { Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.alignByBaseline().padding(end = 8.dp)) }
            Text(parts.digits, style = MaterialTheme.typography.displayLarge.tabular, maxLines = 1, modifier = Modifier.alignByBaseline())
            if (!parts.periodFirst) parts.period?.let { Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.alignByBaseline().padding(start = 8.dp)) }
        }
    }
    val cookie: @Composable (Dp) -> Unit = { size ->
        if (alarm.snooze) {
            RingCookie(stringResource(R.string.button_snooze), null, size, onSnooze, supporting = pluralStringResource(R.plurals.label_minutes_count, snoozeMinutes, snoozeMinutes))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    title()
                    Spacer(Modifier.weight(1f))
                    SlideToStop(onStop)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { cookie(min(this@BoxWithConstraints.maxHeight, 300.dp)) }
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))
                title()
                Spacer(Modifier.weight(1f))
                cookie(min(this@BoxWithConstraints.maxWidth, 248.dp))
                Spacer(Modifier.weight(1f))
                SlideToStop(onStop, Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

/** One ended timer: its label, the time since the end counting up, what it was set to; the cookie stops it. */
@Composable
private fun OneTimer(timer: Timer, now: Moment, onStop: () -> Unit, onAddMinute: () -> Unit) {
    val title: @Composable () -> Unit = {
        if (timer.label.isNotEmpty()) {
            Text(timer.label, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        }
        Text(TimerFormat.left(timer.remaining(now)), style = MaterialTheme.typography.displayLarge.tabular, maxLines = 1)
        Text(
            stringResource(R.string.label_timer_of, durationWords(timer.original)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val addMinute: @Composable (Modifier) -> Unit = { modifier ->
        FilledTonalButton(onClick = onAddMinute, modifier = modifier.fillMaxWidth().height(72.dp)) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null)
            Text(stringResource(R.string.button_add_minute), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            // Landscape (§3.4.4): label, time and the lower action in the start half, the cookie filling the end half
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    title()
                    Spacer(Modifier.weight(1f))
                    addMinute(Modifier)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    RingCookie(stringResource(R.string.button_stop), R.drawable.ic_stop, min(this@BoxWithConstraints.maxHeight, 300.dp), onStop)
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))
                title()
                Spacer(Modifier.weight(1f))
                RingCookie(stringResource(R.string.button_stop), R.drawable.ic_stop, min(this@BoxWithConstraints.maxWidth, 248.dp), onStop)
                Spacer(Modifier.weight(1f))
                addMinute(Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

/** Several timers ended together (§3.4.8): each with its own +1:00 and stop, the one that ended first on top; Stop all below. */
@Composable
private fun SeveralTimers(ringing: List<Timer>, now: Moment, timers: TimerController) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            pluralStringResource(R.plurals.title_timers_ended, ringing.size, ringing.size),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 32.dp, bottom = 24.dp).semantics { heading() },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(ringing, key = { _, timer -> timer.id }) { position, timer ->
                Surface(shape = groupShapes(position, ringing.size).shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(timer.label.ifEmpty { stringResource(R.string.label_timer_default) }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(TimerFormat.left(timer.remaining(now)), style = MaterialTheme.typography.headlineMedium.tabular, maxLines = 1)
                        }
                        FilledTonalButton(onClick = { timers.addMinute(timer.id) }) { Text(stringResource(R.string.label_plus_minute)) }
                        FilledTonalIconButton(onClick = { timers.delete(timer.id) }, modifier = Modifier.size(56.dp)) {
                            Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.button_stop))
                        }
                    }
                }
            }
        }
        Button(onClick = { timers.stopRinging() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).height(80.dp)) {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = null)
            Text(stringResource(R.string.button_stop_all), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/**
 * The ringing button (§3.4.3, the owner's decision): a 12-sided cookie on primary-container that
 * turns at a steady pace while something rings – one turn in 30 seconds, only the shape; what is
 * written on it stands still and upright. With the system's animations off it stands still.
 */
@Composable
fun RingCookie(label: String, icon: Int?, size: Dp, onClick: () -> Unit, supporting: String? = null) {
    val context = LocalContext.current
    val animated = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
    val turn = if (animated) {
        val transition = rememberInfiniteTransition(label = "cookie")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(TURN_MS, easing = LinearEasing), RepeatMode.Restart), label = "turn").value
    } else 0f
    val shape = MaterialShapes.Cookie12Sided.toShape()
    Box(
        Modifier.size(size).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn }.clip(shape).background(MaterialTheme.colorScheme.primaryContainer))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon?.let { Icon(painterResource(it), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) }
            Text(label, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

private const val TURN_MS = 30_000

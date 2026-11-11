@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.stopwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.notify.Notifications
import sh.hnet.clock.stopwatch.LapMark
import sh.hnet.clock.stopwatch.LapRow
import sh.hnet.clock.stopwatch.Stopwatch
import sh.hnet.clock.stopwatch.StopwatchController
import sh.hnet.clock.stopwatch.StopwatchFormat
import sh.hnet.clock.ui.components.DestinationScaffold
import sh.hnet.clock.ui.components.TooltipIconButton
import sh.hnet.clock.ui.components.tabular

/**
 * Stopwatch (SPEC §3.7): the time to hundredths, the three controls in their fixed places (§2.4:
 * Reset, Start/Pause, Lap – disabled rather than hidden) and, from the first lap, the lap list
 * with the time moved to the top. Reset clears at once and offers Undo.
 */
@Composable
fun StopwatchScreen(onOpenSettings: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val context = LocalContext.current
    val controller = (context.applicationContext as ClockApplication).stopwatch
    val stopwatch by controller.state.collectAsStateWithLifecycle()
    val elapsed by rememberElapsed(controller, stopwatch)
    val rows = stopwatch.rows(elapsed)

    // The notification is the only thing here that needs a permission: asked once, at the first start (§3.11.2)
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { controller.sync() }
    val start = {
        controller.start()
        if (!Notifications.allowed(context) && takeFirstAsk(context)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val resetText = stringResource(R.string.msg_stopwatch_reset)
    val undo = stringResource(R.string.button_undo)
    val reset: () -> Unit = {
        val before = controller.reset()
        // In the app's scope: the snackbar and its Undo outlive this page
        messageScope.launch {
            snackbars.currentSnackbarData?.dismiss()
            if (snackbars.showSnackbar(resetText, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                controller.restore(before)
            }
        }
    }
    val shareTitle = stringResource(R.string.label_nav_stopwatch)
    val lapLabel = stringResource(R.string.label_lap_n)
    val share = {
        val text = StopwatchFormat.share(stopwatch, controller.elapsed(), shareTitle) { lapLabel.format(it) }
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    val subtitle = when {
        stopwatch.running -> stringResource(R.string.label_running)
        !stopwatch.isZero -> stringResource(R.string.label_paused)
        else -> null
    }
    DestinationScaffold(stringResource(R.string.label_nav_stopwatch), onOpenSettings, subtitle = subtitle) {
        Column(Modifier.fillMaxSize()) {
            val timeColor = if (stopwatch.running || stopwatch.isZero) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            if (rows.isEmpty()) {
                // No laps: the time alone, centered; no sentence – the list simply isn't there yet (§3.7.4)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Time(elapsed, timeColor) }
            } else {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), contentAlignment = Alignment.Center) { Time(elapsed, timeColor) }
                LapsHeader(stopwatch.laps.size, share)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(rows, key = { it.number }, contentType = { "lap" }) { row -> Lap(row, running = stopwatch.running) }
                }
            }
            Controls(stopwatch, onStart = start, onPause = controller::pause, onLap = controller::lap, onReset = reset)
        }
    }
}

/**
 * The time shown: renewed with every frame while the stopwatch runs and the page is in front, and
 * standing still otherwise.
 */
@Composable
private fun rememberElapsed(controller: StopwatchController, stopwatch: Stopwatch): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(controller.elapsed(), stopwatch, lifecycle) {
        value = controller.elapsed()
        if (stopwatch.running) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) withFrameMillis { value = controller.elapsed() }
        }
    }
}

/** Minutes and seconds in display-large, hundredths in display-small (§3.7.1); read as one time, and not live. */
@Composable
private fun Time(elapsed: Long, color: Color) {
    Row(Modifier.clearAndSetSemantics { contentDescription = StopwatchFormat.full(elapsed) }) {
        Text(StopwatchFormat.main(elapsed), style = MaterialTheme.typography.displayLarge.tabular, color = color, maxLines = 1, modifier = Modifier.alignByBaseline())
        Text(
            StopwatchFormat.hundredths(elapsed),
            style = MaterialTheme.typography.displaySmall.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** "{n} laps", the two column names and the list's own action, Share (§3.7.3). */
@Composable
private fun LapsHeader(count: Int, onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            pluralStringResource(R.plurals.label_laps, count, count),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(stringResource(R.string.label_lap), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(LAP_COLUMN))
        Text(stringResource(R.string.label_total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(TOTAL_COLUMN))
        TooltipIconButton(R.drawable.ic_share, stringResource(R.string.content_description_share_laps), onShare)
    }
}

/** A lap: its number with Fastest or Slowest under it (icon and word, never color alone), its time, the total. */
@Composable
private fun Lap(row: LapRow, running: Boolean) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 56.dp, top = 6.dp, bottom = 6.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.label_lap_n, row.number), style = MaterialTheme.typography.bodyLarge)
            row.mark?.let { mark ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        painterResource(if (mark == LapMark.FASTEST) R.drawable.ic_bolt else R.drawable.ic_hourglass_bottom),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(if (mark == LapMark.FASTEST) R.string.label_fastest else R.string.label_slowest),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            StopwatchFormat.full(row.time),
            style = MaterialTheme.typography.bodyLarge.tabular,
            // The lap being timed counts in primary
            color = if (row.current && running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(LAP_COLUMN),
        )
        Text(
            StopwatchFormat.full(row.total),
            style = MaterialTheme.typography.bodyMedium.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(TOTAL_COLUMN),
        )
    }
}

/**
 * Reset at the start, Start/Pause in the middle, Lap at the end (§2.4, §3.7.2). The primary button
 * tells running from paused by its shape (round while running), as Music's play button does.
 */
@Composable
private fun Controls(stopwatch: Stopwatch, onStart: () -> Unit, onPause: () -> Unit, onLap: () -> Unit, onReset: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onReset,
            enabled = !stopwatch.running && !stopwatch.isZero,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(56.dp),
        ) {
            Icon(painterResource(R.drawable.ic_replay), contentDescription = stringResource(R.string.button_reset))
        }
        FilledIconToggleButton(
            checked = stopwatch.running,
            onCheckedChange = { if (stopwatch.running) onPause() else onStart() },
            shapes = IconButtonDefaults.toggleableShapes(shape = RoundedCornerShape(24.dp), checkedShape = CircleShape),
            // The same colors running or paused: the shape tells them apart
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                checkedContainerColor = MaterialTheme.colorScheme.primary,
                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.size(width = 128.dp, height = 80.dp),
        ) {
            val label = when {
                stopwatch.running -> R.string.button_pause
                stopwatch.isZero -> R.string.button_start
                else -> R.string.button_resume
            }
            Icon(
                painterResource(if (stopwatch.running) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                contentDescription = stringResource(label),
                modifier = Modifier.size(32.dp),
            )
        }
        FilledTonalIconButton(
            onClick = onLap,
            enabled = stopwatch.canLap,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(56.dp),
        ) {
            Icon(painterResource(R.drawable.ic_flag), contentDescription = stringResource(R.string.button_lap))
        }
    }
}

/** True the first time only: the notification permission is asked for once from here, never again after a refusal. */
private fun takeFirstAsk(context: Context): Boolean {
    val store = context.getSharedPreferences("first_run", Context.MODE_PRIVATE)
    if (store.getBoolean(ASKED, false)) return false
    store.edit { putBoolean(ASKED, true) }
    return true
}

private const val ASKED = "notifications_asked"
private val LAP_COLUMN = 96.dp
private val TOTAL_COLUMN = 88.dp

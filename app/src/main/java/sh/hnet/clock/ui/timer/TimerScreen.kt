@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.clock.ui.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.stopwatch.Moment
import sh.hnet.clock.timer.Timer
import sh.hnet.clock.timer.TimerController
import sh.hnet.clock.timer.TimerCounts
import sh.hnet.clock.timer.TimerFormat
import sh.hnet.clock.timer.TimerInput
import sh.hnet.clock.timer.TimerPreset
import sh.hnet.clock.timer.shown
import sh.hnet.clock.ui.components.DestinationScaffold
import sh.hnet.clock.ui.components.TextDialog
import sh.hnet.clock.ui.components.TooltipIconButton
import sh.hnet.clock.ui.components.rememberNotificationAsk
import sh.hnet.clock.ui.components.rememberTimeFormats
import sh.hnet.clock.ui.components.tabular

/**
 * Timer (SPEC §3.6). With no timers the input is the page (presets, the duration, the keypad and
 * Start); one running or paused timer fills it with its ring; several, or one that has ended, are
 * a list of cards, the ended first. More timers are set in a sheet with the same input.
 */
@Composable
fun TimerScreen(onOpenSettings: () -> Unit, onEditPresets: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val context = LocalContext.current
    val controller = (context.applicationContext as ClockApplication).timers
    val timers by controller.timers.collectAsStateWithLifecycle()
    val presets by controller.presets.collectAsStateWithLifecycle()
    val now by rememberMoment(controller, ticking = timers.any { it.running })
    val shown = timers.shown(now)

    // While this page is in front, a timer ending turns its card instead of opening the ringing page (§3.6.7)
    LifecycleResumeEffect(controller) {
        controller.pageVisible = true
        onPauseOrDispose { controller.pageVisible = false }
    }

    val askNotifications = rememberNotificationAsk { controller.refresh() }
    val start: (Long, String) -> Unit = { duration, label ->
        controller.start(duration, label)
        askNotifications(false)
    }

    var input by rememberSaveable(stateSaver = InputSaver) { mutableStateOf(TimerInput()) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var labelFor by rememberSaveable { mutableStateOf<Long?>(null) }

    val deletedNamed = stringResource(R.string.msg_timer_deleted)
    val deletedPlain = stringResource(R.string.msg_timer_deleted_unnamed)
    val undo = stringResource(R.string.button_undo)
    val delete: (Timer) -> Unit = { timer ->
        controller.delete(timer.id)?.let { removed ->
            // In the app's scope: the snackbar and its Undo outlive this page
            messageScope.launch {
                snackbars.currentSnackbarData?.dismiss()
                val text = if (removed.label.isEmpty()) deletedPlain else AnnotatedString.fromHtml(deletedNamed.format(removed.label.htmlEncode())).text
                if (snackbars.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    controller.restore(removed)
                }
            }
        }
    }

    val counts = TimerCounts.of(timers, now)
    val subtitle = listOfNotNull(
        if (counts.running > 0) pluralStringResource(R.plurals.label_timers_running, counts.running, counts.running) else null,
        if (counts.paused > 0) pluralStringResource(R.plurals.label_timers_paused, counts.paused, counts.paused) else null,
        if (counts.ended > 0) pluralStringResource(R.plurals.label_timers_ended, counts.ended, counts.ended) else null,
    ).joinToString(", ").ifEmpty { null }

    DestinationScaffold(stringResource(R.string.label_nav_timer), onOpenSettings, subtitle = subtitle) {
        val single = shown.singleOrNull()?.takeIf { !it.ended(now) }
        Column(Modifier.fillMaxSize()) {
            when {
                shown.isEmpty() -> {
                    // No timers: the input is the page, and its own empty state (§3.6.3)
                    PresetChips(presets, onStart = { start(it.duration, it.name) }, onEdit = onEditPresets, modifier = Modifier.padding(top = 4.dp))
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DurationKeypad(input, { input = it })
                    }
                    StartButton(enabled = !input.isZero, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        start(input.duration, "")
                        input = TimerInput()
                    }
                }
                single != null -> {
                    OneTimer(
                        single, now, controller,
                        onLabel = { labelFor = single.id },
                        onDelete = { delete(single) },
                        modifier = Modifier.weight(1f),
                    )
                    PresetChips(presets, onStart = { start(it.duration, it.name) }, onEdit = onEditPresets)
                    NewTimerButton(enabled = timers.size < TimerController.MAX_TIMERS) { sheetOpen = true }
                }
                else -> {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(shown, key = { it.id }, contentType = { "timer" }) { timer ->
                            TimerCard(timer, now, controller, onLabel = { labelFor = timer.id }, onDelete = { delete(timer) }, modifier = Modifier.animateItem())
                        }
                    }
                    NewTimerButton(enabled = timers.size < TimerController.MAX_TIMERS) { sheetOpen = true }
                }
            }
        }
    }

    if (sheetOpen) {
        // The same input in a sheet, so setting a timer always looks the same (§3.6.6)
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var sheetInput by rememberSaveable(stateSaver = InputSaver) { mutableStateOf(TimerInput()) }
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = sheet) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                PresetChips(presets, onStart = { start(it.duration, it.name); sheetOpen = false }, onEdit = { sheetOpen = false; onEditPresets() })
                DurationKeypad(sheetInput, { sheetInput = it }, Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
                StartButton(enabled = !sheetInput.isZero, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    start(sheetInput.duration, "")
                    sheetOpen = false
                }
            }
        }
    }

    labelFor?.let { id ->
        val timer = timers.firstOrNull { it.id == id }
        if (timer == null) labelFor = null else TextDialog(
            title = stringResource(R.string.label_label),
            fieldLabel = stringResource(R.string.label_label),
            initial = timer.label,
            maxLength = TimerController.MAX_LABEL,
            onDismiss = { labelFor = null },
            onConfirm = { controller.setLabel(id, it); labelFor = null },
        )
    }
}

/** The moment the page shows: renewed with every frame while a timer runs and the page is in front. */
@Composable
private fun rememberMoment(controller: TimerController, ticking: Boolean): State<Moment> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(controller.now(), ticking, lifecycle) {
        value = controller.now()
        if (ticking) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) withFrameMillis { value = controller.now() }
        }
    }
}

private val InputSaver = androidx.compose.runtime.saveable.Saver<TimerInput, String>(save = { it.digits }, restore = { TimerInput(it) })

/** Start: filled, full width, in the dock; disabled until a time is typed (§3.6.3). */
@Composable
private fun StartButton(enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hint = stringResource(R.string.msg_enter_time_first)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp).semantics { if (!enabled) stateDescription = hint },
    ) {
        Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null)
        Text(stringResource(R.string.button_start), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun NewTimerButton(enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).height(56.dp)) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
        Text(stringResource(R.string.button_new_timer), modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * One timer filling the page (§3.6.4): its label (a button: opens the label dialog) and Delete; the
 * wavy ring – wavy for the time left, flat when paused – with the time left and when it ends
 * inside; Reset, Pause/Resume and +1:00 in their fixed places (§2.4).
 */
@Composable
private fun OneTimer(timer: Timer, now: Moment, controller: TimerController, onLabel: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val formats = rememberTimeFormats()
    val paused = !timer.running
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onLabel, modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    timer.label.ifEmpty { stringResource(R.string.label_timer_default) },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.content_description_edit_label), modifier = Modifier.padding(start = 8.dp).size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            DeleteButton(onDelete)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            // The ring gives way first when the page is short (§5.3)
            Box(Modifier.sizeIn(maxWidth = 264.dp, maxHeight = 264.dp).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator(
                    progress = { timer.fraction(now) },
                    modifier = Modifier.fillMaxSize(),
                    amplitude = { if (paused) 0f else 1f },
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        TimerFormat.left(timer.remaining(now)),
                        style = MaterialTheme.typography.displayLarge.tabular,
                        color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        if (paused) stringResource(R.string.label_paused)
                        else stringResource(R.string.label_ends_at, formats.time(java.time.Instant.ofEpochMilli(timer.endsAt(now)), java.time.ZoneId.systemDefault())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = { controller.reset(timer.id) }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_replay), contentDescription = stringResource(R.string.button_reset))
            }
            PauseResume(timer, controller, Modifier.size(width = 128.dp, height = 80.dp), iconSize = 32)
            FilledTonalButton(onClick = { controller.addMinute(timer.id) }, modifier = Modifier.height(56.dp)) {
                Text(stringResource(R.string.label_plus_minute), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * A timer as a card (§3.6.5): label with Reset and Delete, then the time left with +1:00 and
 * Pause/Resume, then the wavy line. Ended (§3.6.7), it turns primary-container and counts up below
 * zero, with +1:00 and Stop.
 */
@Composable
private fun TimerCard(timer: Timer, now: Moment, controller: TimerController, onLabel: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val ended = timer.ended(now)
    val paused = !timer.running
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (ended) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (ended) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onLabel, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        timer.label.ifEmpty { stringResource(R.string.label_timer_default) },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (ended) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!ended) {
                    TooltipIconButton(R.drawable.ic_replay, stringResource(R.string.button_reset), { controller.reset(timer.id) })
                    DeleteButton(onDelete)
                } else Spacer(Modifier.height(48.dp))
            }
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    TimerFormat.left(timer.remaining(now)),
                    style = MaterialTheme.typography.headlineLarge.tabular,
                    color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { controller.addMinute(timer.id) }) { Text(stringResource(R.string.label_plus_minute)) }
                if (ended) {
                    Button(onClick = { controller.delete(timer.id) }) {
                        Icon(painterResource(R.drawable.ic_stop), contentDescription = null)
                        Text(stringResource(R.string.button_stop), modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    PauseResume(timer, controller, Modifier.size(56.dp), iconSize = 24)
                }
            }
            if (!ended) {
                LinearWavyProgressIndicator(
                    progress = { timer.fraction(now) },
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                    amplitude = { if (paused) 0f else 1f },
                )
            }
        }
    }
}

/** The primary button: round while the timer runs, a rounded square when paused, as the stopwatch's. */
@Composable
private fun PauseResume(timer: Timer, controller: TimerController, modifier: Modifier, iconSize: Int) {
    FilledIconToggleButton(
        checked = timer.running,
        onCheckedChange = { if (timer.running) controller.pause(timer.id) else controller.resume(timer.id) },
        shapes = IconButtonDefaults.toggleableShapes(shape = RoundedCornerShape(if (iconSize > 24) 24.dp else 16.dp), checkedShape = CircleShape),
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier,
    ) {
        Icon(
            painterResource(if (timer.running) R.drawable.ic_pause else R.drawable.ic_play_arrow),
            contentDescription = stringResource(if (timer.running) R.string.button_pause else R.string.button_resume),
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

@Composable
private fun DeleteButton(onDelete: () -> Unit) {
    IconButton(onClick = onDelete, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
        Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.button_delete))
    }
}

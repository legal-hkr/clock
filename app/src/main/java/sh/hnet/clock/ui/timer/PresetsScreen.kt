@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.timer.Timer
import sh.hnet.clock.timer.TimerController
import sh.hnet.clock.timer.TimerFormat
import sh.hnet.clock.timer.TimerPreset
import sh.hnet.clock.ui.components.LazySettingsPage
import sh.hnet.clock.ui.components.SwipeToRemove
import sh.hnet.clock.ui.components.SETTINGS_GROUP_GAP
import sh.hnet.clock.ui.components.SETTINGS_ROW_GAP
import sh.hnet.clock.ui.components.dragHandle
import sh.hnet.clock.ui.components.durationWords
import sh.hnet.clock.ui.components.groupShapes
import sh.hnet.clock.ui.components.moveActions
import sh.hnet.clock.ui.components.rememberReorderState
import sh.hnet.clock.ui.components.reorderableItem
import sh.hnet.clock.ui.components.settingsRowColors

/**
 * Timer presets (SPEC §3.6.9): name and duration per row; a tap edits, the handle reorders (saved on
 * drop), a swipe removes at once with Undo; Add preset below. Reached from the chips on Timer and
 * from Settings › Timers.
 */
@Composable
fun PresetsScreen(onBack: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val controller = (LocalContext.current.applicationContext as ClockApplication).timers
    val presets by controller.presets.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }

    // Local order while dragging; the saved order leads otherwise
    val rows = remember { mutableStateListOf<TimerPreset>() }
    val reorder = rememberReorderState(
        listState = list,
        canMove = { key -> key is Long },
        onStart = {},
        onMove = { from, to ->
            val a = rows.indexOfFirst { it.id == from }
            val b = rows.indexOfFirst { it.id == to }
            if (a >= 0 && b >= 0) rows.add(b, rows.removeAt(a))
        },
        onDrop = { controller.setPresets(rows.toList()) },
    )
    LaunchedEffect(presets, reorder.draggedKey) {
        if (reorder.draggedKey == null) {
            rows.clear()
            rows.addAll(presets)
        }
    }

    val removedText = stringResource(R.string.msg_preset_removed)
    val undo = stringResource(R.string.button_undo)
    val remove: (TimerPreset) -> Unit = { preset ->
        val position = controller.removePreset(preset.id)
        if (position >= 0) messageScope.launch {
            snackbars.currentSnackbarData?.dismiss()
            val text = AnnotatedString.fromHtml(removedText.format(preset.name.htmlEncode())).text
            if (snackbars.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                controller.insertPreset(preset, position)
            }
        }
    }

    LazySettingsPage(title = stringResource(R.string.title_timer_presets), onBack = onBack, state = list) {
        if (rows.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.msg_empty_presets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        itemsIndexed(rows, key = { _, preset -> preset.id }, contentType = { _, _ -> "preset" }) { position, preset ->
            val dragged = reorder.draggedKey == preset.id
            val moves = moveActions(position, rows.size) { to ->
                val moved = rows.toMutableList()
                moved.add(to, moved.removeAt(position))
                controller.setPresets(moved)
            }
            val removeAction = CustomAccessibilityAction(stringResource(R.string.label_remove)) { remove(preset); true }
            val shapes = if (dragged) {
                ListItemDefaults.shapes(shape = LIFTED, selectedShape = LIFTED, pressedShape = LIFTED, focusedShape = LIFTED, hoveredShape = LIFTED, draggedShape = LIFTED)
            } else groupShapes(position, rows.size)
            Box(
                Modifier
                    .then(if (dragged) Modifier else Modifier.animateItem())
                    .padding(bottom = if (position < rows.lastIndex) SETTINGS_ROW_GAP else 0.dp)
                    .reorderableItem(reorder, preset.id, liftedShape = LIFTED, liftedElevation = 1.dp),
            ) {
                SwipeToRemove(shapes.shape, enabled = !dragged, onRemove = { remove(preset) }) {
                    SegmentedListItem(
                        onClick = { editing = preset.id },
                        shapes = shapes,
                        colors = if (dragged) ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) else settingsRowColors(),
                        supportingContent = { Text(durationWords(preset.duration)) },
                        trailingContent = {
                            Box(Modifier.size(48.dp).dragHandle(reorder, preset.id), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_drag_indicator), contentDescription = stringResource(R.string.content_description_reorder, preset.name))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().semantics { customActions = moves + removeAction },
                    ) {
                        Text(preset.name)
                    }
                }
            }
        }
        item(key = "add") {
            FilledTonalButton(
                onClick = { adding = true },
                enabled = presets.size < TimerController.MAX_PRESETS,
                modifier = Modifier.fillMaxWidth().padding(top = SETTINGS_GROUP_GAP).height(56.dp),
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Text(stringResource(R.string.button_add_preset), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (adding) {
        PresetDialog(null, onDismiss = { adding = false }) { name, duration ->
            controller.addPreset(name, duration)
            adding = false
        }
    }
    editing?.let { id ->
        val preset = presets.firstOrNull { it.id == id }
        if (preset == null) editing = null else PresetDialog(preset, onDismiss = { editing = null }) { name, duration ->
            controller.updatePreset(id, name, duration)
            editing = null
        }
    }
}

/**
 * "Add a preset" / "Edit a preset": the name, then the time as three number fields in one row,
 * each with its unit as a suffix. What is wrong is said when Save is tapped: a missing name in its
 * field, the time (none at all, minutes or seconds over 59) in one line under the row.
 */
@Composable
private fun PresetDialog(preset: TimerPreset?, onDismiss: () -> Unit, onSave: (String, Long) -> Unit) {
    val parts = preset?.let { TimerFormat.parts(it.duration) }
    var name by rememberSaveable { mutableStateOf(preset?.name.orEmpty()) }
    var hours by rememberSaveable { mutableStateOf(parts?.first?.takeIf { it > 0 }?.toString().orEmpty()) }
    var minutes by rememberSaveable { mutableStateOf(parts?.second?.takeIf { it > 0 }?.toString().orEmpty()) }
    var seconds by rememberSaveable { mutableStateOf(parts?.third?.takeIf { it > 0 }?.toString().orEmpty()) }
    var checked by rememberSaveable { mutableStateOf(false) }

    val h = hours.toIntOrNull() ?: 0
    val m = minutes.toIntOrNull() ?: 0
    val s = seconds.toIntOrNull() ?: 0
    val nameMissing = name.isBlank()
    val noTime = h == 0 && m == 0 && s == 0
    val save = {
        checked = true
        if (!nameMissing && !noTime && m <= 59 && s <= 59) onSave(name, ((h * 3600L + m * 60 + s) * 1000).coerceAtMost(Timer.MAX))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (preset == null) R.string.title_add_preset else R.string.title_edit_preset)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= TimerController.MAX_PRESET_NAME) name = it },
                    label = { Text(stringResource(R.string.label_name)) },
                    singleLine = true,
                    isError = checked && nameMissing,
                    supportingText = if (checked && nameMissing) ({ Text(stringResource(R.string.error_enter_name)) }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                // One caption for the three fields and one line for what is wrong with them: neither fits inside a field this narrow
                Text(stringResource(R.string.label_duration), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val timeError = when {
                    !checked -> null
                    noTime -> R.string.error_enter_time
                    m > 59 || s > 59 -> R.string.error_up_to_59
                    else -> null
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(hours, { hours = it }, R.string.label_unit_h, R.string.label_hours, isError = checked && noTime, Modifier.weight(1f))
                    NumberField(minutes, { minutes = it }, R.string.label_unit_min, R.string.label_minutes, isError = checked && (noTime || m > 59), Modifier.weight(1f))
                    NumberField(seconds, { seconds = it }, R.string.label_unit_s, R.string.label_seconds, isError = checked && (noTime || s > 59), Modifier.weight(1f))
                }
                timeError?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.button_save)) } },
    )
}

/**
 * A two-digit number with its unit as the suffix ("3 min") and a muted 0 while empty; the unit's full
 * name is what a screen reader says. The keyboard is the digits-only pad.
 */
@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, unit: Int, name: Int, isError: Boolean, modifier: Modifier) {
    val spoken = stringResource(name)
    OutlinedTextField(
        value = value,
        onValueChange = { text -> if (text.length <= 2 && text.all { it.isDigit() }) onChange(text) },
        placeholder = { Text("0") },
        suffix = { Text(stringResource(unit)) },
        singleLine = true,
        isError = isError,
        // NumberPassword is the pad with digits alone (Number adds signs and separators); nothing is masked without a visual transformation
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier.semantics { contentDescription = spoken },
    )
}

private val LIFTED = RoundedCornerShape(16.dp)

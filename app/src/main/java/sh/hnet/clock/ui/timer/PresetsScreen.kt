@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package sh.hnet.clock.ui.timer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.timer.TimerController
import sh.hnet.clock.timer.TimerInput
import sh.hnet.clock.timer.TimerPreset
import sh.hnet.clock.ui.components.LazySettingsPage
import sh.hnet.clock.ui.components.SETTINGS_GROUP_GAP
import sh.hnet.clock.ui.components.SETTINGS_ROW_GAP
import sh.hnet.clock.ui.components.SwipeToRemove
import sh.hnet.clock.ui.components.dragHandle
import sh.hnet.clock.ui.components.durationWords
import sh.hnet.clock.ui.components.groupShapes
import sh.hnet.clock.ui.components.moveActions
import sh.hnet.clock.ui.components.rememberReorderState
import sh.hnet.clock.ui.components.reorderableItem
import sh.hnet.clock.ui.components.settingsRowColors

/**
 * Timer presets (SPEC §3.6.9): name and duration per row (the duration alone without a name); a tap edits, the handle reorders (saved on
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
    val resources = LocalResources.current
    val remove: (TimerPreset) -> Unit = { preset ->
        val position = controller.removePreset(preset.id)
        if (position >= 0) messageScope.launch {
            snackbars.currentSnackbarData?.dismiss()
            val text = AnnotatedString.fromHtml(removedText.format(preset.name.ifEmpty { durationWords(resources, preset.duration) }.htmlEncode())).text
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
                        // A preset without a name is its duration, on one line
                        supportingContent = if (preset.name.isEmpty()) null else ({ Text(durationWords(preset.duration)) }),
                        trailingContent = {
                            Box(Modifier.size(48.dp).dragHandle(reorder, preset.id), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_drag_indicator), contentDescription = stringResource(R.string.content_description_reorder, preset.name.ifEmpty { durationWords(preset.duration) }))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().semantics { customActions = moves + removeAction },
                    ) {
                        Text(preset.name.ifEmpty { durationWords(preset.duration) })
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
        PresetSheet(null, onDismiss = { adding = false }) { name, duration ->
            controller.addPreset(name, duration)
            adding = false
        }
    }
    editing?.let { id ->
        val preset = presets.firstOrNull { it.id == id }
        if (preset == null) editing = null else PresetSheet(preset, onDismiss = { editing = null }) { name, duration ->
            controller.updatePreset(id, name, duration)
            editing = null
        }
    }
}

/**
 * "Add a preset" / "Edit a preset" (handoff of 12 Nov 2026, §4): the New timer sheet with a name on
 * top and Save in place of Start. The name is optional. The duration is typed on the timer's own
 * keypad; of a preset being edited, the first digit starts it anew and backspace edits it. While
 * the name has the focus the system keyboard takes the keypad's place – one keyboard at a time –
 * and Done, or a tap on the duration, brings the keypad back. Leaving after a change asks first.
 */
@Composable
private fun PresetSheet(preset: TimerPreset?, onDismiss: () -> Unit, onSave: (String, Long) -> Unit) {
    val initial = remember { preset?.let { TimerInput.of(it.duration) } ?: TimerInput() }
    var name by rememberSaveable { mutableStateOf(preset?.name.orEmpty()) }
    var input by rememberSaveable(stateSaver = InputSaver) { mutableStateOf(initial) }
    // The duration as it came with the preset: the first digit replaces it
    var untouched by rememberSaveable { mutableStateOf(preset != null) }
    var asking by rememberSaveable { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val changed = { name.trim() != preset?.name.orEmpty() || input.duration != initial.duration }

    val scope = rememberCoroutineScope()
    var nameFocused by remember { mutableStateOf(false) }
    // Leaving the name moves the focus to the duration: merely clearing it in a sheet's window hands it straight back to the field
    val duration = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val leaveName = {
        if (nameFocused) duration.requestFocus()
        keyboardController?.hide()
    }
    // The keyboard closed by Back leaves the field too: the keypad returns
    val keyboard = WindowInsets.isImeVisible
    LaunchedEffect(keyboard) { if (!keyboard && nameFocused) duration.requestFocus() }

    // Back, the scrim and a swipe down all ask the sheet to hide: after a change it stays, and asks
    val sheet = rememberBottomSheetState(
        SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden && !discarding && changed()) {
                asking = true
                false
            } else true
        },
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(if (preset == null) R.string.title_add_preset else R.string.title_edit_preset),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).semantics { heading() },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= TimerController.MAX_PRESET_NAME) name = it },
                    label = { Text(stringResource(R.string.label_name_optional)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { leaveName() }),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp).onFocusChanged { nameFocused = it.isFocused },
                )
                DurationDisplay(
                    input,
                    Modifier
                        .padding(top = 12.dp, bottom = 16.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .focusRequester(duration)
                        .focusable()
                        .clickable(enabled = nameFocused) { leaveName() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                AnimatedVisibility(visible = !nameFocused) {
                    Keypad(
                        // On an untouched duration the first digit stands alone; backspace edits what is there
                        onType = { key ->
                            input = (if (untouched) TimerInput() else input).type(key)
                            untouched = false
                        },
                        onBackspace = {
                            input = input.backspace()
                            untouched = false
                        },
                        onClear = {
                            input = TimerInput()
                            untouched = false
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            val hint = stringResource(R.string.msg_enter_time_first)
            Button(
                onClick = { onSave(name.trim(), input.duration) },
                enabled = !input.isZero,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp).height(56.dp).semantics { if (input.isZero) stateDescription = hint },
            ) {
                Text(stringResource(R.string.button_save))
            }
        }
    }

    if (asking) {
        // Nothing saved is lost, so Discard isn't an error button
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.title_discard_changes)) },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.button_keep_editing)) } },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    discarding = true
                    scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
                }) { Text(stringResource(R.string.button_discard)) }
            },
        )
    }
}

private val LIFTED = RoundedCornerShape(16.dp)

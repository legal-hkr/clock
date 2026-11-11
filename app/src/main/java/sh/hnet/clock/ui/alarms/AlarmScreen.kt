@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.clock.ui.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.reliability.Ringing
import sh.hnet.clock.ui.components.ProblemCard
import sh.hnet.clock.ui.components.rememberProblemFix
import sh.hnet.clock.ui.components.rememberReliability
import sh.hnet.clock.ui.settings.rememberSystemSounds
import sh.hnet.clock.ui.settings.soundGoneText
import sh.hnet.clock.R
import sh.hnet.clock.alarm.Alarm
import sh.hnet.clock.alarm.Days
import sh.hnet.clock.ring.SoundChoice
import sh.hnet.clock.ui.components.DestructiveButton
import sh.hnet.clock.ui.components.RowIcon
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.SettingsNavigationRow
import sh.hnet.clock.ui.components.SettingsPage
import sh.hnet.clock.ui.components.SettingsSwitchRow
import sh.hnet.clock.ui.components.TextDialog
import sh.hnet.clock.ui.components.groupShapes
import sh.hnet.clock.ui.components.rememberNotificationAsk
import sh.hnet.clock.ui.components.rememberNow
import sh.hnet.clock.ui.components.rememberTimeFormats
import sh.hnet.clock.ui.components.settingsRowColors
import sh.hnet.clock.ui.components.tabular
import sh.hnet.clock.ui.settings.soundName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * An alarm's page (SPEC §3.2.3): every change is saved at once, as on the settings pages – no Save,
 * no "discard changes?", no way to leave believing something was kept that wasn't. Top to bottom:
 * the time with the switch; Repeat, Date (a one-off alarm's) and Label; Sound, Vibration and
 * Snooze; Skip the next one (a repeating alarm that is on); Delete alarm.
 */
@Composable
fun AlarmScreen(id: Long, onBack: () -> Unit, onOpenSound: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val controller = (LocalContext.current.applicationContext as ClockApplication).alarms
    val resources = LocalResources.current
    val formats = rememberTimeFormats()
    val alarms by controller.alarms.collectAsStateWithLifecycle()
    val settings by controller.settings.collectAsStateWithLifecycle()
    val now by rememberNow(everySecond = false)
    val zone = ZoneId.systemDefault()
    val alarm = alarms.firstOrNull { it.id == id }
    // Deleted (here, or by Undo's absence elsewhere): nothing to show
    LaunchedEffect(alarm == null) { if (alarm == null) onBack() }
    alarm ?: return

    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var editingLabel by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberNotificationAsk { controller.refresh() }
    val deletedText = stringResource(R.string.msg_alarm_deleted)
    val undo = stringResource(R.string.button_undo)
    val sound = SoundChoice.decode(alarm.sound, alarm.soundTitle)
    val silent = sound == SoundChoice.Silent
    // What could keep this alarm from ringing, while it is on (§3.2.8)
    val reliability = rememberReliability()
    val problem = reliability.checks.problems(Ringing.ALARMS, soundWanted = !silent).firstOrNull()?.takeIf { alarm.enabled }
    val fix = rememberProblemFix(Ringing.ALARMS, reliability.refresh)
    val soundGone = soundGoneText(sound, rememberSystemSounds())

    SettingsPage(title = stringResource(R.string.title_alarm), onBack = onBack) {
        // The time, a button that opens the picker; under it when it rings; the alarm's switch at the end
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    formats.time(alarm, zone),
                    style = MaterialTheme.typography.displayLarge.tabular,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClickLabel = stringResource(R.string.title_select_time)) { pickingTime = true },
                )
                val pending = alarm.pending(zone)
                // With a problem nothing is promised here: the card below says what is wrong
                if (problem == null) Text(
                    when {
                        alarm.snoozedUntil != null -> stringResource(R.string.msg_snoozed_until, formats.time(Instant.ofEpochMilli(alarm.snoozedUntil), zone))
                        pending != null -> ringsInText(pending - now.toEpochMilli())
                        else -> stringResource(R.string.label_state_off)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = alarm.enabled,
                onCheckedChange = { on ->
                    controller.setEnabled(id, on)
                    if (on) askNotifications(false)
                },
            )
        }
        problem?.let { ProblemCard(it, Ringing.ALARMS, { fix(it) }) }
        SettingsGroup {
            val rows = if (alarm.repeating) 2 else 3
            row { _ -> RepeatRow(alarm, groupShapes(0, rows).shape, daysLine(alarm)) { days -> controller.reschedule(id) { it.copy(days = days, date = if (days.isEmpty()) it.date else null) } } }
            if (!alarm.repeating) {
                row { shapes ->
                    SegmentedListItem(
                        onClick = { pickingDate = true },
                        shapes = shapes,
                        colors = settingsRowColors(),
                        leadingContent = { RowIcon(R.drawable.ic_event) },
                        supportingContent = { Text(daysText(resources, formats, alarm, now, zone)) },
                    ) { Text(stringResource(R.string.label_date)) }
                }
            }
            row { shapes ->
                SegmentedListItem(
                    onClick = { editingLabel = true },
                    shapes = shapes,
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_label) },
                    supportingContent = { Text(alarm.label.ifEmpty { stringResource(R.string.label_none) }) },
                ) { Text(stringResource(R.string.label_label)) }
            }
        }
        SettingsGroup {
            row { shapes -> SettingsNavigationRow(shapes, if (silent) R.drawable.ic_music_off else R.drawable.ic_music_note, stringResource(R.string.label_sound), soundGone ?: soundName(sound), onClick = onOpenSound) }
            row { shapes ->
                // A silent alarm without vibration would be an alarm that doesn't ring (§3.3.6): on, and locked
                SegmentedListItem(
                    checked = alarm.vibration || silent,
                    onCheckedChange = { on -> controller.update(id) { it.copy(vibration = on) } },
                    enabled = !silent,
                    shapes = shapes,
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_vibration) },
                    trailingContent = { Switch(checked = alarm.vibration || silent, onCheckedChange = null, enabled = !silent) },
                    supportingContent = if (silent) ({ Text(stringResource(R.string.desc_vibration_locked)) }) else null,
                ) { Text(stringResource(R.string.label_vibration)) }
            }
            row { shapes ->
                SettingsSwitchRow(
                    shapes, R.drawable.ic_snooze, stringResource(R.string.button_snooze),
                    checked = alarm.snooze, onCheckedChange = { on -> controller.update(id) { it.copy(snooze = on) } },
                    supporting = if (alarm.snooze) pluralStringResource(R.plurals.label_minutes_count, settings.snoozeMinutes, settings.snoozeMinutes) else stringResource(R.string.label_off),
                )
            }
        }
        if (alarm.repeating && alarm.enabled) {
            // The ringing that is, or would be, left out
            val skippable = alarm.skipped ?: alarm.occurrenceAfter(alarm.since, zone)
            SettingsGroup {
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, R.drawable.ic_event_busy, stringResource(R.string.label_skip_next),
                        checked = alarm.skipped != null, onCheckedChange = { on -> controller.setSkipNext(id, on) },
                        supporting = skippable?.let { dateTimeText(resources, formats, it, now, zone) },
                    )
                }
            }
        }
        // The page's single destructive action: no dialog – back to the list, with Undo (§3.2.7)
        DestructiveButton(stringResource(R.string.button_delete_alarm), R.drawable.ic_delete, {
            deleteWithUndo(controller.delete(id), deletedText.format(formats.time(alarm, zone).htmlEncode()), undo, snackbars, messageScope) { controller.restore(it) }
        }, Modifier.padding(top = 8.dp))
    }

    if (pickingTime) {
        TimeDialog(alarm.hour, alarm.minute, onDismiss = { pickingTime = false }) { hour, minute ->
            pickingTime = false
            controller.reschedule(id) { it.copy(hour = hour, minute = minute) }
        }
    }
    if (pickingDate) {
        // The picker works in UTC days: today and later can be picked (§3.2.4)
        val today = now.atZone(zone).toLocalDate()
        val first = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val shownDay = (alarm.next(zone) ?: alarm.occurrenceAfter(now.toEpochMilli(), zone))?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today
        val state = rememberDatePickerState(
            initialSelectedDateMillis = shownDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= first
                override fun isSelectableYear(year: Int) = year >= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingDate = false
                    state.selectedDateMillis?.let { millis ->
                        val date: LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        controller.reschedule(id) { it.copy(date = date.toString()) }
                    }
                }) { Text(stringResource(R.string.button_save)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.button_cancel)) } },
        ) { DatePicker(state) }
    }
    if (editingLabel) {
        TextDialog(
            title = stringResource(R.string.label_label),
            fieldLabel = stringResource(R.string.label_label),
            initial = alarm.label,
            maxLength = Alarm.MAX_LABEL,
            onDismiss = { editingLabel = false },
            onConfirm = { text -> controller.update(id) { it.copy(label = text) }; editingLabel = false },
        )
    }
}

/** "Weekdays", "Mon, Wed, Fri", "Every day" – or "Once" with no day chosen. */
@Composable
private fun daysLine(alarm: Alarm): String {
    val formats = rememberTimeFormats()
    return if (alarm.repeating) daysText(LocalResources.current, formats, alarm, Instant.now(), ZoneId.systemDefault()) else stringResource(R.string.label_once)
}

/**
 * Repeat (§3.2.3): the result in words, then seven connected toggle buttons from the system's first
 * day of the week, each showing the day's shortest name and saying its full one.
 */
@Composable
private fun RepeatRow(alarm: Alarm, shape: androidx.compose.ui.graphics.Shape, result: String, onChange: (Set<Int>) -> Unit) {
    val formats = rememberTimeFormats()
    val group = stringResource(R.string.label_repeat_on)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(stringResource(R.string.label_repeat), style = MaterialTheme.typography.bodyLarge)
                Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth().semantics { contentDescription = group },
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                val week = Days.week(formats.firstDay)
                week.forEachIndexed { index, day ->
                    val name = formats.dayFull(day)
                    ToggleButton(
                        checked = day.value in alarm.days,
                        onCheckedChange = { on -> onChange(if (on) alarm.days + day.value else alarm.days - day.value) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            week.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).height(40.dp).semantics { contentDescription = name; role = Role.Checkbox },
                    ) {
                        Text(formats.dayNarrow(day), maxLines = 1)
                    }
                }
            }
        }
    }
}

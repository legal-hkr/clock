@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.alarms

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.alarm.Alarm
import sh.hnet.clock.alarm.nextPending
import sh.hnet.clock.alarm.shown
import sh.hnet.clock.ui.components.DestinationScaffold
import sh.hnet.clock.ui.components.EmptyState
import sh.hnet.clock.ui.components.FAB_CLEARANCE
import sh.hnet.clock.ui.components.LocalSnackbarLift
import sh.hnet.clock.ui.components.SwipeToRemove
import sh.hnet.clock.ui.components.TimeFormats
import sh.hnet.clock.ui.components.groupShapes
import sh.hnet.clock.ui.components.rememberNotificationAsk
import sh.hnet.clock.ui.components.rememberNow
import sh.hnet.clock.ui.components.rememberTimeFormats
import sh.hnet.clock.ui.components.tabular
import java.time.Instant
import java.time.ZoneId

/**
 * Alarms (SPEC §3.2.1): the alarms by time of day, each with its switch; the bar says when the
 * next one rings. A tap opens an alarm's page, a swipe removes it with Undo, the FAB asks for a
 * time and the alarm exists from that tap on (§3.2.2).
 */
@Composable
fun AlarmsScreen(onOpenSettings: () -> Unit, onOpenAlarm: (Long) -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val controller = (LocalContext.current.applicationContext as ClockApplication).alarms
    val resources = LocalResources.current
    val formats = rememberTimeFormats()
    val alarms by controller.alarms.collectAsStateWithLifecycle()
    val now by rememberNow(everySecond = false)
    val zone = ZoneId.systemDefault()
    val shown = alarms.shown()
    var adding by rememberSaveable { mutableStateOf(false) }

    // Seen: the "missed" notes have done their work once the page is left (§3.4.6)
    LifecycleResumeEffect(controller) { onPauseOrDispose { controller.markSeen() } }

    val askNotifications = rememberNotificationAsk { controller.refresh() }
    /** After an alarm is switched on or saved: when it rings, said once (§3.2.1), and the permission it needs asked for. */
    val announce: (Long) -> Unit = { id ->
        askNotifications(false)
        controller.alarms.value.firstOrNull { it.id == id }?.pending(zone)?.let { moment ->
            messageScope.launch {
                snackbars.currentSnackbarData?.dismiss()
                snackbars.showSnackbar(ringsInText(resources, moment - System.currentTimeMillis()), duration = SnackbarDuration.Short)
            }
        }
    }
    val deletedText = stringResource(R.string.msg_alarm_deleted)
    val undo = stringResource(R.string.button_undo)
    val delete: (Alarm) -> Unit = { alarm -> deleteWithUndo(controller.delete(alarm.id), deletedText.format(formats.time(alarm, zone).htmlEncode()), undo, snackbars, messageScope) { controller.restore(it) } }

    val snoozed = alarms.mapNotNull { it.snoozedUntil }.minOrNull()
    val pending = alarms.nextPending(zone)
    val subtitle = when {
        alarms.isEmpty() -> null
        snoozed != null -> stringResource(R.string.msg_snoozed_until, formats.time(Instant.ofEpochMilli(snoozed), zone))
        pending != null -> ringsInText(pending.second - now.toEpochMilli())
        else -> stringResource(R.string.label_no_alarms_on)
    }
    DestinationScaffold(stringResource(R.string.label_nav_alarms), onOpenSettings, subtitle = subtitle) {
        if (shown.isEmpty()) {
            EmptyState(R.drawable.ic_alarm, stringResource(R.string.msg_empty_alarms), Modifier.padding(bottom = FAB_CLEARANCE))
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = FAB_CLEARANCE),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(shown, key = { _, alarm -> alarm.id }, contentType = { _, _ -> "alarm" }) { position, alarm ->
                    val shape = groupShapes(position, shown.size).shape
                    Column(Modifier.animateItem()) {
                        SwipeToRemove(shape, onRemove = { delete(alarm) }) {
                            AlarmRow(
                                alarm, formats, now, zone, shape,
                                onOpen = { onOpenAlarm(alarm.id) },
                                onSwitch = { on ->
                                    controller.setEnabled(alarm.id, on)
                                    if (on) announce(alarm.id)
                                },
                                onDismissSnooze = { controller.stop(alarm.id) },
                                onUndoSkip = { controller.setSkipNext(alarm.id, false) },
                                onDelete = { delete(alarm) },
                            )
                        }
                    }
                }
            }
        }
        val lift by animateDpAsState(LocalSnackbarLift.current, MaterialTheme.motionScheme.defaultSpatialSpec())
        FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp + lift)) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.button_add_alarm))
        }
    }

    if (adding) {
        // The next full hour to start from
        val start = now.atZone(zone).plusHours(1).hour
        TimeDialog(start, 0, onDismiss = { adding = false }) { hour, minute ->
            adding = false
            controller.add(hour, minute)?.let { alarm ->
                announce(alarm.id)
                onOpenAlarm(alarm.id)
            }
        }
    }
}

/** An alarm's time of day as the system writes it ("06:30", "6:30 AM"). */
fun TimeFormats.time(alarm: Alarm, zone: ZoneId): String =
    time(java.time.LocalDate.now(zone).atTime(alarm.time).atZone(zone).toInstant(), zone)

/** Removes at once and offers Undo; the snackbar outlives the page it was started from. */
fun <T> deleteWithUndo(removed: T?, message: String, undo: String, snackbars: SnackbarHostState, scope: CoroutineScope, restore: (T) -> Unit) {
    removed ?: return
    scope.launch {
        snackbars.currentSnackbarData?.dismiss()
        val text = AnnotatedString.fromHtml(message).text
        if (snackbars.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) restore(removed)
    }
}

/**
 * An alarm in the list: its time, then its days or date with the label after them in parentheses
 * ("Weekdays (Work)"), its switch as a target of its own; under them the notes for what is going
 * on – snoozed, next one skipped, missed – each with the one action that takes it back.
 */
@Composable
private fun AlarmRow(
    alarm: Alarm, formats: TimeFormats, now: Instant, zone: ZoneId, shape: Shape,
    onOpen: () -> Unit, onSwitch: (Boolean) -> Unit, onDismissSnooze: () -> Unit, onUndoSkip: () -> Unit, onDelete: () -> Unit,
) {
    val resources = LocalResources.current
    val time = formats.time(alarm, zone)
    val days = daysText(resources, formats, alarm, now, zone)
    val line = if (alarm.label.isEmpty()) days else stringResource(R.string.label_days_and_label, days, alarm.label)
    val state = stringResource(if (alarm.enabled) R.string.label_state_on else R.string.label_state_off)
    val deleteAction = CustomAccessibilityAction(stringResource(R.string.button_delete)) { onDelete(); true }
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onOpen).heightIn(min = 88.dp).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { customActions = listOf(deleteAction) }) {
                    Text(
                        time,
                        style = MaterialTheme.typography.displaySmall.tabular,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onSwitch,
                    modifier = Modifier.semantics {
                        contentDescription = if (alarm.label.isEmpty()) time else "$time, ${alarm.label}"
                        stateDescription = state
                    },
                )
            }
            alarm.snoozedUntil?.let { until ->
                Note(R.drawable.ic_snooze, stringResource(R.string.msg_snoozed_until, formats.time(Instant.ofEpochMilli(until), zone)), stringResource(R.string.button_dismiss), onDismissSnooze)
            }
            alarm.missed?.let { missed ->
                Note(R.drawable.ic_notifications_paused, stringResource(R.string.msg_missed, whenText(resources, formats, missed, now, zone)), null) {}
            }
            alarm.skipped?.let { skipped ->
                Note(R.drawable.ic_event_busy, stringResource(R.string.msg_skipping, formats.shortDate(Instant.ofEpochMilli(skipped), zone, now)), stringResource(R.string.button_undo), onUndoSkip)
            }
        }
    }
}

/** A note line under an alarm: an 18 dp icon and a sentence in primary, with at most one text button at its end. */
@Composable
private fun Note(icon: Int, text: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        action?.let { TextButton(onClick = onAction) { Text(it) } }
    }
}

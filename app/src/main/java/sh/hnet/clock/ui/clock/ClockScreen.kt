@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.clock

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.alarm.nextPending
import sh.hnet.clock.ui.alarms.whenText
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.ui.platform.LocalResources
import sh.hnet.clock.time.TimeDifference
import sh.hnet.clock.time.ZoneNames
import sh.hnet.clock.time.isAway
import sh.hnet.clock.time.mergeOrder
import sh.hnet.clock.time.zoneOrNull
import sh.hnet.clock.ui.components.DestinationScaffold
import sh.hnet.clock.ui.components.SwipeToRemove
import sh.hnet.clock.ui.components.FAB_CLEARANCE
import sh.hnet.clock.ui.components.LocalSnackbarLift
import sh.hnet.clock.ui.components.TimeFormats
import sh.hnet.clock.ui.components.dragAfterLongPress
import sh.hnet.clock.ui.components.groupShapes
import sh.hnet.clock.ui.components.moveActions
import sh.hnet.clock.ui.components.rememberNow
import sh.hnet.clock.ui.components.rememberReorderState
import sh.hnet.clock.ui.components.rememberTimeFormats
import sh.hnet.clock.ui.components.reorderableItem
import sh.hnet.clock.ui.components.tabular
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Clock (SPEC §3.5): the local time and date, then the user's cities with their difference from
 * local time. Cities reorder by press and hold and go by a swipe, with Undo. Away from home (§3.5.6)
 * the bar says whose time "local" is and a Home row leads the list.
 */
@Composable
fun ClockScreen(
    viewModel: ClockViewModel,
    onOpenSettings: () -> Unit,
    onAddCity: () -> Unit,
    onOpenAlarms: () -> Unit,
    snackbars: SnackbarHostState,
    messageScope: CoroutineScope,
) {
    val app = LocalContext.current.applicationContext as ClockApplication
    val locale = LocalConfiguration.current.locales[0]
    val zones = remember(locale) { app.zoneNames(locale) }
    val formats = rememberTimeFormats()
    val stored by viewModel.cities.collectAsStateWithLifecycle()
    val showSeconds by viewModel.showSeconds.collectAsStateWithLifecycle()
    val homeClock by viewModel.homeClock.collectAsStateWithLifecycle()
    val homeZoneId by viewModel.homeZone.collectAsStateWithLifecycle()
    val now by rememberNow(everySecond = showSeconds)

    // Read with every tick: the phone's zone changes under the app when traveling
    val local = ZoneNames.systemZone()
    val home = homeZoneId?.let(::zoneOrNull)
    val away = isAway(homeClock, home, local, now)
    val localId = remember(local, zones) { zones.canonical(local.id) }
    val homeId = remember(homeZoneId, zones) { homeZoneId?.let(zones::canonical) }
    // While away, the Home row stands for the home city, and the city of the zone the phone is in equals local time: both leave the list
    val visible = remember(stored, away, localId, homeId, zones) {
        stored.orEmpty().filter { id ->
            zoneOrNull(id) != null && !(away && zones.canonical(id).let { it == localId || it == homeId })
        }
    }
    // The next alarm, as a chip under the date that opens Alarms (§3.5.1)
    val alarms by app.alarms.alarms.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val nextAlarm = alarms.nextPending(local)?.second?.let { whenText(resources, formats, it, now, local) }
    val alarmChip: @Composable () -> Unit = {
        nextAlarm?.let { text ->
            AssistChip(
                onClick = onOpenAlarms,
                label = { Text(text) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_alarm), contentDescription = stringResource(R.string.content_description_next_alarm), modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
    val names = remember(visible, homeId, localId, zones) {
        (visible + listOfNotNull(homeId, localId)).associateWith { zones.city(it).name }
    }

    val list = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    // Local order while dragging; the stored order leads otherwise
    val rows = remember { mutableStateListOf<String>() }
    val reorder = rememberReorderState(
        listState = list,
        canMove = { key -> key in rows },
        onStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
        onMove = { from, to ->
            val a = rows.indexOf(from)
            val b = rows.indexOf(to)
            if (a >= 0 && b >= 0) rows.add(b, rows.removeAt(a))
        },
        onDrop = { viewModel.setOrder(mergeOrder(stored.orEmpty(), rows.toList())) },
    )
    LaunchedEffect(visible, reorder.draggedKey) {
        if (reorder.draggedKey == null && rows.toList() != visible) {
            rows.clear()
            rows.addAll(visible)
        }
    }
    val leading = if (away) 2 else 1
    // A city just added: scrolled to once, when its row is there
    LaunchedEffect(rows.size) {
        val added = viewModel.justAdded ?: return@LaunchedEffect
        val position = rows.indexOf(added)
        if (position >= 0) {
            viewModel.justAdded = null
            list.animateScrollToItem(leading + position)
        }
    }

    val removedText = stringResource(R.string.msg_city_removed)
    val undo = stringResource(R.string.button_undo)
    val remove: (String) -> Unit = { id ->
        val name = names[id].orEmpty()
        // In the app's scope: the snackbar and its Undo outlive this page
        messageScope.launch {
            val position = viewModel.remove(id)
            if (position < 0) return@launch
            snackbars.currentSnackbarData?.dismiss()
            val text = AnnotatedString.fromHtml(removedText.format(name.htmlEncode())).text
            if (snackbars.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                viewModel.restore(id, position)
            }
        }
    }

    val subtitle = if (away) stringResource(R.string.label_city_time, names[localId].orEmpty()) else null
    DestinationScaffold(stringResource(R.string.label_nav_clock), onOpenSettings, subtitle = subtitle) {
        if (stored != null && rows.isEmpty() && visible.isEmpty() && !away) {
            // No cities: the local time is the page, centered (§3.5.5)
            Box(Modifier.fillMaxSize().padding(bottom = FAB_CLEARANCE), contentAlignment = Alignment.Center) {
                LocalTime(formats, now, local, showSeconds, centered = true, chip = alarmChip)
            }
        } else {
            val count = rows.size + if (away) 1 else 0
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = FAB_CLEARANCE),
            ) {
                item(key = "local", contentType = "local") {
                    LocalTime(formats, now, local, showSeconds, centered = false, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 24.dp), chip = alarmChip)
                }
                if (away && home != null && homeId != null) {
                    item(key = "home", contentType = "home") {
                        CityRow(
                            name = stringResource(R.string.label_home_city, names[homeId].orEmpty()),
                            difference = differenceText(TimeDifference.between(local, home, now), formats, now, home),
                            time = formats.time(now, home),
                            shape = groupShapes(0, count).shape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            home = true,
                            modifier = Modifier.padding(bottom = if (count > 1) ROW_GAP else 0.dp),
                        )
                    }
                }
                itemsIndexed(rows, key = { _, id -> id }, contentType = { _, _ -> "city" }) { position, id ->
                    val zone = zoneOrNull(id) ?: return@itemsIndexed
                    val dragged = reorder.draggedKey == id
                    val name = names[id] ?: zones.city(id).name
                    val moves = moveActions(position, rows.size) { to ->
                        val moved = rows.toMutableList()
                        moved.add(to, moved.removeAt(position))
                        viewModel.setOrder(mergeOrder(stored.orEmpty(), moved))
                    }
                    val removeAction = CustomAccessibilityAction(stringResource(R.string.label_remove)) { remove(id); true }
                    val shape = if (dragged) LIFTED_SHAPE else groupShapes(position + count - rows.size, count).shape
                    // The gap goes outside the lifted layer, so the shadow falls only under the row
                    Box(
                        Modifier
                            .then(if (dragged) Modifier else Modifier.animateItem())
                            .padding(bottom = if (position < rows.lastIndex) ROW_GAP else 0.dp)
                            .reorderableItem(reorder, id, liftedShape = LIFTED_SHAPE, liftedElevation = 1.dp)
                            .dragAfterLongPress(reorder, id)
                            .semantics { customActions = moves + removeAction },
                    ) {
                        SwipeToRemove(shape, enabled = !dragged, onRemove = { remove(id) }) {
                            CityRow(
                                name = name,
                                difference = differenceText(TimeDifference.between(local, zone, now), formats, now, zone),
                                time = formats.time(now, zone),
                                shape = shape,
                                color = if (dragged) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                            )
                        }
                    }
                }
            }
        }
        val lift by animateDpAsState(LocalSnackbarLift.current, MaterialTheme.motionScheme.defaultSpatialSpec())
        FloatingActionButton(
            onClick = onAddCity,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp + lift),
        ) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.button_add_city))
        }
    }
}

/**
 * The local time block (§3.5.1): time in display-large with tabular figures, seconds after it in
 * display-small when shown, the period of a 12-hour clock in title-large (§5.6); under it the date
 * written out. Read as one item, and not a live region: it would never stop talking.
 */
@Composable
private fun LocalTime(formats: TimeFormats, now: Instant, zone: ZoneId, showSeconds: Boolean, centered: Boolean, modifier: Modifier = Modifier, chip: @Composable () -> Unit = {}) {
    val parts = formats.parts(now, zone)
    val date = formats.date(now, zone)
    val spoken = formats.time(now, zone) + ", " + date
    Column(modifier = modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
      Column(
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
      ) {
        Row {
            val period: @Composable (Modifier) -> Unit = { m ->
                parts.period?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = m.alignByBaseline()) }
            }
            if (parts.periodFirst) period(Modifier.padding(end = 8.dp))
            Text(parts.digits, style = MaterialTheme.typography.displayLarge.tabular, maxLines = 1, modifier = Modifier.alignByBaseline())
            if (showSeconds) {
                Text(
                    parts.seconds,
                    style = MaterialTheme.typography.displaySmall.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            if (!parts.periodFirst) period(Modifier.padding(start = 8.dp))
        }
        Text(date, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
        chip()
    }
}

/** A city (§3.5.3): its name, the difference in words, its time at the end; read as one item. */
@Composable
private fun CityRow(name: String, difference: String, time: String, shape: Shape, color: Color, modifier: Modifier = Modifier, home: Boolean = false) {
    Surface(shape = shape, color = color, modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(
            Modifier.heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (home) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_home_filled), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(difference, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(time, style = MaterialTheme.typography.titleLarge.tabular, maxLines = 1)
        }
    }
}

/**
 * The difference from local time in words (§3.5.3): "Same time", "1 hour ahead", "6 hours behind",
 * "4 h 30 min ahead", "45 min behind"; with the day before it when the date there is another one
 * ("Tomorrow, 8 hours ahead"; a weekday across the date line, where it can be two days away).
 */
@Composable
private fun differenceText(difference: TimeDifference, formats: TimeFormats, now: Instant, zone: ZoneId): String {
    val minutes = abs(difference.minutes)
    val h = minutes / 60
    val m = minutes % 60
    val ahead = difference.minutes > 0
    val amount = when {
        minutes == 0 -> stringResource(R.string.label_same_time)
        m == 0 -> pluralStringResource(if (ahead) R.plurals.label_hours_ahead else R.plurals.label_hours_behind, h, h)
        h == 0 -> stringResource(if (ahead) R.string.label_min_ahead else R.string.label_min_behind, m)
        else -> stringResource(if (ahead) R.string.label_hm_ahead else R.string.label_hm_behind, h, m)
    }
    val day = when (difference.dayShift) {
        0 -> return amount
        1 -> stringResource(R.string.label_tomorrow)
        -1 -> stringResource(R.string.label_yesterday)
        else -> formats.weekday(now, zone)
    }
    return stringResource(R.string.label_day_and_difference, day, amount)
}

private val ROW_GAP = 2.dp

/** A lifted row: radius 16, elevation 1. */
private val LIFTED_SHAPE = RoundedCornerShape(16.dp)

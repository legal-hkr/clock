@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.clock.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Badge
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.clock.R

/**
 * A settings page, as Music's: a small bar with Back (a large flexible one without [onBack]) and
 * under it a scrolling column of groups. Settings open over the destinations, without the navigation
 * bar, so the content keeps clear of the system's navigation area itself.
 *
 * @param onBack Null for a page without Back
 */
@Composable
fun SettingsPage(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    /** False when the page is drawn inside something that already took the status bar's inset. */
    topInsets: Boolean = true,
    /** Stays under the top bar while the content scrolls. */
    pinned: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = if (onBack == null) TopAppBarDefaults.exitUntilCollapsedScrollBehavior() else TopAppBarDefaults.pinnedScrollBehavior()
    Column(Modifier.fillMaxSize()) {
        if (onBack == null) {
            LargeFlexibleTopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                scrollBehavior = scrollBehavior,
            )
        } else {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, modifier = Modifier.semantics { heading() })
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
                windowInsets = if (topInsets) TopAppBarDefaults.windowInsets else WindowInsets(0),
                scrollBehavior = scrollBehavior,
            )
        }
        pinned?.invoke()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * A settings page whose content is a lazy list, for rows that reorder by dragging (the drag
 * component works on a LazyListState). Same bar and padding as [SettingsPage]; the caller spaces
 * its groups ([SETTINGS_GROUP_GAP] between groups, [SETTINGS_ROW_GAP] between a group's rows).
 */
@Composable
fun LazySettingsPage(
    title: String,
    onBack: () -> Unit,
    state: LazyListState,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, modifier = Modifier.semantics { heading() }) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
            scrollBehavior = scrollBehavior,
        )
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            content = content,
        )
    }
}

val SETTINGS_GROUP_GAP = 16.dp
val SETTINGS_ROW_GAP = 2.dp

/** A group's header, as [SettingsGroup] draws it, for a group laid out row by row in a lazy list. */
@Composable
fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** An icon-only button with its label as content description and as a tooltip on long press. */
@Composable
fun TooltipIconButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(painterResource(icon), contentDescription = label)
        }
    }
}

/** Rows of a [SettingsGroup]; each gets the shapes of its place in the group. */
class SettingsGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun row(content: @Composable (shapes: ListItemShapes) -> Unit) {
        rows += content
    }
}

/** A group of segmented rows, 2 dp apart, round outer and small inner corners; optionally titled. */
@Composable
fun SettingsGroup(title: String? = null, content: SettingsGroupScope.() -> Unit) {
    val scope = SettingsGroupScope().apply(content)
    if (scope.rows.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        title?.let { SettingsGroupHeader(it) }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec()),
        ) {
            scope.rows.forEachIndexed { index, row -> row(groupShapes(index, scope.rows.size)) }
        }
    }
}

/**
 * Shapes of a row by its place in the group. A group of one row gets the group's outer corners
 * (taken from the library's first and last segments) rather than the single-item shape.
 */
@Composable
fun groupShapes(index: Int, count: Int): ListItemShapes {
    val segmented = ListItemDefaults.segmentedShapes(index, count)
    if (count > 1) return segmented
    val first = ListItemDefaults.segmentedShapes(0, 2).shape as? CornerBasedShape ?: return segmented
    val last = ListItemDefaults.segmentedShapes(1, 2).shape as? CornerBasedShape ?: return segmented
    val outer = first.copy(bottomStart = last.bottomStart, bottomEnd = last.bottomEnd)
    return ListItemDefaults.shapes(
        shape = outer,
        selectedShape = outer,
        pressedShape = segmented.pressedShape,
        focusedShape = segmented.focusedShape,
        hoveredShape = segmented.hoveredShape,
        draggedShape = segmented.draggedShape,
    )
}

/** Segments on surfaceContainer, so a group stands out from the page as one block. */
@Composable
fun settingsRowColors(): ListItemColors =
    ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** The tonal leading icon box of a row; [error] for a row reporting a problem. */
@Composable
fun RowIcon(@DrawableRes icon: Int, error: Boolean = false) {
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content)
    }
}

/** A row opening a page; [badge] (e.g. "New") before the chevron, as in ComfyChair. */
@Composable
fun SettingsNavigationRow(
    shapes: ListItemShapes,
    @DrawableRes icon: Int,
    title: String,
    supporting: String? = null,
    badge: String? = null,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RowIcon(icon) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                badge?.let {
                    Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Text(it, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
            }
        },
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(title)
    }
}

/** A row handing an address to another app (the browser): it leaves Clock, so `open_in_new`, not a chevron. */
@Composable
fun SettingsLinkRow(shapes: ListItemShapes, @DrawableRes icon: Int, title: String, supporting: String? = null, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RowIcon(icon) },
        trailingContent = { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null) },
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(title)
    }
}

/** A boolean setting: the whole row toggles, the switch shows the state. */
@Composable
fun SettingsSwitchRow(
    shapes: ListItemShapes,
    @DrawableRes icon: Int,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RowIcon(icon) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(title)
    }
}

/** One choice of a single-choice group (put the group in a selectableGroup); the chosen one keeps the component's selected colors. */
@Composable
fun SettingsRadioRow(shapes: ListItemShapes, title: String, selected: Boolean, onSelect: () -> Unit, supporting: String? = null) {
    SegmentedListItem(
        selected = selected,
        onClick = onSelect,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(title)
    }
}

/** A page's explanation above its groups. */
@Composable
fun SettingsDescription(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

/** Secondary text under a group. */
@Composable
fun SettingsNote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 16.dp))
}

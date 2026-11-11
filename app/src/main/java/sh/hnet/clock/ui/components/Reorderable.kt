package sh.hnet.clock.ui.components

import sh.hnet.clock.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * Drag-to-reorder for a [LazyListState] (our own, no library): the dragged row follows the
 * finger, swaps with a neighbor once it passes the neighbor's middle, and the list scrolls when
 * the row reaches an edge. The caller reorders its own list on [onMove] while dragging and
 * commits once on [onDrop], so the player sees one move per drag.
 */
@Stable
class ReorderState internal constructor(
    val listState: LazyListState,
    private val edgePx: Float,
) {
    var draggedKey by mutableStateOf<Any?>(null)
        private set

    /** Where the dragged row's top should be, in the list's viewport coordinates. */
    private var desiredTop by mutableFloatStateOf(0f)

    internal var onStart: (key: Any) -> Unit = {}
    internal var onMove: (fromKey: Any, toKey: Any) -> Unit = { _, _ -> }
    internal var onDrop: (key: Any) -> Unit = {}
    internal var canMove: (key: Any) -> Boolean = { true }

    private fun info(key: Any?): LazyListItemInfo? = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** How far the dragged row is drawn from where the list placed it. */
    fun offsetOf(key: Any): Float {
        if (key != draggedKey) return 0f
        val placed = info(key) ?: return 0f
        return desiredTop - placed.offset
    }

    internal fun start(key: Any) {
        val placed = info(key) ?: return
        draggedKey = key
        desiredTop = placed.offset.toFloat()
        onStart(key)
    }

    internal fun drag(delta: Float) {
        draggedKey ?: return
        desiredTop += delta
        swapIfNeeded()
    }

    internal fun swapIfNeeded() {
        val key = draggedKey ?: return
        val dragged = info(key) ?: return
        val middle = desiredTop + dragged.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            other.key != key && canMove(other.key) && middle > other.offset && middle < other.offset + other.size &&
                // Only once past the neighbor's middle, so rows don't flip back and forth
                (if (other.index > dragged.index) middle > other.offset + other.size / 2f else middle < other.offset + other.size / 2f)
        } ?: return
        onMove(key, target.key)
    }

    internal fun end() {
        val key = draggedKey ?: return
        draggedKey = null
        onDrop(key)
    }

    /** Pixels per frame to scroll when the dragged row touches an edge; 0 otherwise. */
    internal fun autoScrollSpeed(): Float {
        val key = draggedKey ?: return 0f
        val dragged = info(key) ?: return 0f
        val viewportStart = listState.layoutInfo.viewportStartOffset.toFloat()
        val viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat()
        val top = desiredTop
        val bottom = desiredTop + dragged.size
        return when {
            top < viewportStart + edgePx -> -((viewportStart + edgePx - top) / edgePx).coerceIn(0f, 1f) * 24f
            bottom > viewportEnd - edgePx -> ((bottom - (viewportEnd - edgePx)) / edgePx).coerceIn(0f, 1f) * 24f
            else -> 0f
        }
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    canMove: (key: Any) -> Boolean,
    onStart: (key: Any) -> Unit,
    onMove: (fromKey: Any, toKey: Any) -> Unit,
    onDrop: (key: Any) -> Unit,
): ReorderState {
    val edgePx = with(LocalDensity.current) { 64.dp.toPx() }
    val state = remember(listState) { ReorderState(listState, edgePx) }
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val can by rememberUpdatedState(canMove)
    val begin by rememberUpdatedState(onStart)
    state.onStart = { begin(it) }
    state.onMove = { a, b -> move(a, b) }
    state.onDrop = { drop(it) }
    state.canMove = { can(it) }
    // Scrolls while the dragged row is held at an edge, swapping as rows pass under it
    LaunchedEffect(state, state.draggedKey) {
        while (state.draggedKey != null) {
            val speed = state.autoScrollSpeed()
            if (speed != 0f) {
                listState.scrollBy(speed)
                state.swapIfNeeded()
            }
            delay(16)
        }
    }
    return state
}

/**
 * The row's own modifier: lifted above the others and drawn where the finger is while dragged.
 * The shadow takes [liftedShape] (the lifted row's own shape) and [liftedElevation]; it falls
 * exactly under the row only when nothing (a gap, padding) sits between this modifier and it.
 */
fun Modifier.reorderableItem(
    state: ReorderState,
    key: Any,
    liftedShape: Shape = RectangleShape,
    liftedElevation: Dp = 6.dp,
): Modifier = this
    .zIndex(if (state.draggedKey == key) 1f else 0f)
    .graphicsLayer {
        translationY = state.offsetOf(key)
        if (state.draggedKey == key) {
            shadowElevation = liftedElevation.toPx()
            shape = liftedShape
        }
    }

/**
 * A drag handle: the drag starts on touch, without waiting for the touch slop. Waiting would race
 * the list's scrolling and the bottom sheet's own drag for the same movement, and lose to them
 * when the sheet is fully expanded; owning the gesture from the first touch can't lose.
 */
fun Modifier.dragHandle(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        state.start(key)
        try {
            drag(down.id) { change ->
                state.drag(change.positionChange().y)
                change.consume()
            }
        } finally {
            state.end()
        }
    }
}

/**
 * Reordering by press and hold anywhere on the row (SPEC §3.5.4), for rows with no room for a
 * handle: the drag starts with the long press, so it takes nothing from the list's scrolling or a
 * row's own swipe.
 */
fun Modifier.dragAfterLongPress(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectDragGesturesAfterLongPress(
        onDragStart = { state.start(key) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
    ) { change, amount ->
        change.consume()
        state.drag(amount.y)
    }
}

/**
 * Move up / Move down (and, with [toEnds], Move to top / bottom) for TalkBack on a reorderable row
 * at [position] of [count]; [move] takes the new position.
 */
@Composable
fun moveActions(position: Int, count: Int, toEnds: Boolean = false, move: (Int) -> Unit): List<CustomAccessibilityAction> {
    val up = stringResource(R.string.label_move_up)
    val down = stringResource(R.string.label_move_down)
    val top = stringResource(R.string.label_move_to_top)
    val bottom = stringResource(R.string.label_move_to_bottom)
    return buildList {
        if (position > 0) {
            add(CustomAccessibilityAction(up) { move(position - 1); true })
            if (toEnds) add(CustomAccessibilityAction(top) { move(0); true })
        }
        if (position < count - 1) {
            add(CustomAccessibilityAction(down) { move(position + 1); true })
            if (toEnds) add(CustomAccessibilityAction(bottom) { move(count - 1); true })
        }
    }
}

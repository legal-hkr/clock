package sh.hnet.clock.ring

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.clock.R
import kotlin.math.roundToInt

/**
 * Stop, as a deliberate move (SPEC §3.4.3): the thumb is dragged along its track to the far end;
 * let go before 85 % of the way, it returns. Stopping an alarm by mistake costs the morning, so it
 * gets a place, a shape and a gesture of its own, none of which a grip or a sleepy palm can make.
 *
 * Our own component – Material has no slide-to-act (the owner's exception, §10.2 #9). To
 * accessibility services it is a plain button, "Stop alarm": no drag needed with TalkBack, switch
 * access or a keyboard. Mirrors in right-to-left layouts.
 */
@Composable
fun SlideToStop(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val offset = remember { Animatable(0f) }
    val label = stringResource(R.string.content_description_stop_alarm)
    val returning = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    // The gesture outlives recompositions: it must stop what rings now, not what rang when it was set up
    val stop by rememberUpdatedState(onStop)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(TRACK)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                onClick { stop(); true }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val travel = with(density) { (maxWidth - THUMB - INSET * 2).toPx() }.coerceAtLeast(1f)
        Row(Modifier.align(Alignment.Center).padding(start = THUMB), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.label_slide_to_stop), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            // The two chevrons read as one mark: their 24 dp boxes overlap, leaving the glyphs a stroke apart
            Row(horizontalArrangement = Arrangement.spacedBy(CHEVRON_OVERLAP)) {
                repeat(2) { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Box(
            Modifier
                .padding(horizontal = INSET)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(THUMB)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(travel, rtl) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (offset.value >= travel * THRESHOLD) stop() else scope.launch { offset.animateTo(0f, returning) } },
                        onDragCancel = { scope.launch { offset.animateTo(0f, returning) } },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + if (rtl) -amount else amount).coerceIn(0f, travel)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_alarm_off), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

private val TRACK = 72.dp
private val THUMB = 64.dp
private val INSET = 4.dp
private val CHEVRON_OVERLAP = (-14).dp
private const val THRESHOLD = 0.85f

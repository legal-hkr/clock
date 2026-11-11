package sh.hnet.clock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import sh.hnet.clock.R

/**
 * A swipe to either side removes the row, the error container with the delete icon showing behind
 * it (SPEC §3.2.7); the caller removes at once and offers Undo. Clipped to the row's own [shape].
 */
@Composable
fun SwipeToRemove(shape: Shape, enabled: Boolean = true, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) {
            onRemove()
            // Saved per key: a row put back by Undo would remove itself again
            dismiss.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        modifier = Modifier.clip(shape),
        backgroundContent = {
            val direction = dismiss.dismissDirection
            if (direction != SwipeToDismissBoxValue.Settled) Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

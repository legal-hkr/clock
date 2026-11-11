@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.clock.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import sh.hnet.clock.R

/**
 * A destination's frame (SPEC §3.1.3): a small top bar with the destination's name and, as its
 * subtitle, its state in words (§2.3; absent while there is nothing to say), and Settings as the
 * bar's only control (§3.1.2 – the owner's exception to "no actions in the top bar": it is
 * navigation, the same on all four destinations).
 *
 * @param subtitleColor The error color while the state is a problem
 */
@Composable
fun DestinationScaffold(
    title: String,
    onOpenSettings: () -> Unit,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            actions = { TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.content_description_settings), onOpenSettings) },
        )
        Box(Modifier.fillMaxSize()) { content() }
    }
}

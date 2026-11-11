package sh.hnet.clock.ui.stopwatch

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import sh.hnet.clock.R
import sh.hnet.clock.ui.components.DestinationScaffold

/** Stopwatch (SPEC §3.7). Until its stage: the destination's frame alone. */
@Composable
fun StopwatchScreen(onOpenSettings: () -> Unit) {
    DestinationScaffold(stringResource(R.string.label_nav_stopwatch), onOpenSettings) {}
}

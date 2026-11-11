package sh.hnet.clock.ui.timer

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import sh.hnet.clock.R
import sh.hnet.clock.ui.components.DestinationScaffold

/** Timer (SPEC §3.6). Until its stage: the destination's frame alone. */
@Composable
fun TimerScreen(onOpenSettings: () -> Unit) {
    DestinationScaffold(stringResource(R.string.label_nav_timer), onOpenSettings) {}
}

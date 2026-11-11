package sh.hnet.clock.ui.clock

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import sh.hnet.clock.R
import sh.hnet.clock.ui.components.DestinationScaffold

/** Clock (SPEC §3.5). Until its stage: the destination's frame alone. */
@Composable
fun ClockScreen(onOpenSettings: () -> Unit) {
    DestinationScaffold(stringResource(R.string.label_nav_clock), onOpenSettings) {}
}

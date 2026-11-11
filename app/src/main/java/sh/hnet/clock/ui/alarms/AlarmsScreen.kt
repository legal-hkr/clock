package sh.hnet.clock.ui.alarms

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import sh.hnet.clock.R
import sh.hnet.clock.ui.components.DestinationScaffold
import sh.hnet.clock.ui.components.EmptyState

/** Alarms (SPEC §3.2). Until the alarms stage: the destination's frame and its empty state. */
@Composable
fun AlarmsScreen(onOpenSettings: () -> Unit) {
    DestinationScaffold(stringResource(R.string.label_nav_alarms), onOpenSettings) {
        EmptyState(R.drawable.ic_alarm, stringResource(R.string.msg_empty_alarms))
    }
}

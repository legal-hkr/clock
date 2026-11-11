package sh.hnet.clock.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The height a showing snackbar takes above the navigation bar; a destination's FAB rises by it (SPEC §3.2.1). */
val LocalSnackbarLift = compositionLocalOf { 0.dp }

/** Room a list leaves at its end for the FAB over it (SPEC §5.7). */
val FAB_CLEARANCE: Dp = 88.dp

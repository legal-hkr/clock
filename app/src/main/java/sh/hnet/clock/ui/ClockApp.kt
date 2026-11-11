@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.about.WhatsNew
import sh.hnet.clock.ui.alarms.AlarmsScreen
import sh.hnet.clock.ui.clock.ClockScreen
import sh.hnet.clock.ui.settings.AboutScreen
import sh.hnet.clock.ui.settings.AppLicenseScreen
import sh.hnet.clock.ui.settings.BackupScreen
import sh.hnet.clock.ui.settings.LanguageScreen
import sh.hnet.clock.ui.settings.LicensesScreen
import sh.hnet.clock.ui.settings.ResetScreen
import sh.hnet.clock.ui.settings.SettingsRootScreen
import sh.hnet.clock.ui.settings.WhatsNewScreen
import sh.hnet.clock.ui.stopwatch.StopwatchScreen
import sh.hnet.clock.ui.timer.TimerScreen

/**
 * The app's shell (SPEC §3.1): one back stack whose root holds the four destinations with the
 * navigation bar; Settings and its pages open over it, with Back and without the bar.
 *
 * @param startTab The destination to open on (the one used last)
 * @param restored True when the first-run wizard ended with a restore: says so once
 */
@Composable
fun ClockApp(startTab: Tab, restored: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as ClockApplication
    val scope = rememberCoroutineScope()
    val stack = rememberNavBackStack(Screen.Home)

    var tab by rememberSaveable { mutableStateOf(startTab) }
    // System back on a destination returns to the one before it, once, then leaves the app (§3.1.7)
    var previousTab by rememberSaveable { mutableStateOf<Tab?>(null) }
    fun select(next: Tab) {
        if (next == tab) return
        previousTab = tab
        tab = next
        scope.launch { app.preferences.setLastDestination(next) }
    }

    val snackbars = remember { SnackbarHostState() }
    val restoredText = stringResource(R.string.msg_restored)
    fun showRestored() = scope.launch {
        snackbars.currentSnackbarData?.dismiss()
        snackbars.showSnackbar(restoredText, duration = SnackbarDuration.Short)
    }
    LaunchedEffect(Unit) { if (restored) showRestored() }

    // After an update, once: "Updated to x.y.z" with What's new (as ComfyChair and Music)
    val updatedTo = stringResource(R.string.whats_new_updated_snackbar)
    val whatsNew = stringResource(R.string.whats_new_title)
    val appContext = context.applicationContext
    LaunchedEffect(Unit) {
        val version = WhatsNew.takeUpdateNotice(appContext) ?: return@LaunchedEffect
        if (snackbars.showSnackbar(updatedTo.format(version), actionLabel = whatsNew, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            stack.add(Screen.Settings)
            stack.add(Screen.SettingsAbout)
            stack.add(Screen.SettingsWhatsNew)
        }
    }

    val motion = MaterialTheme.motionScheme
    val spatial: FiniteAnimationSpec<IntOffset> = motion.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = motion.defaultEffectsSpec()
    val fastEffects: FiniteAnimationSpec<Float> = motion.fastEffectsSpec()
    val density = LocalDensity.current
    val shift = with(density) { 30.dp.roundToPx() }

    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val open: (Screen) -> Unit = { stack.add(it) }
    val openSettings = { open(Screen.Settings) }
    // The navigation bar's height (with the system's navigation area), which snackbars stay above on the destinations
    var barHeight by remember { mutableStateOf(0.dp) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = stack,
                onBack = back,
                // Shared axis X between the destinations and the pages over them
                transitionSpec = {
                    (slideInHorizontally(spatial) { shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { -shift } + fadeOut(fastEffects))
                },
                popTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                predictivePopTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                entryProvider = entryProvider<NavKey> {
                    entry<Screen.Home> {
                        BackHandler(enabled = previousTab != null) {
                            previousTab?.let { tab = it; scope.launch { app.preferences.setLastDestination(it) } }
                            previousTab = null
                        }
                        Column(Modifier.fillMaxSize()) {
                            AnimatedContent(
                                targetState = tab,
                                // Fade through between destinations
                                transitionSpec = { (fadeIn(effects) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(fastEffects) },
                                modifier = Modifier.weight(1f),
                                label = "destination",
                            ) { current ->
                                when (current) {
                                    Tab.ALARMS -> AlarmsScreen(openSettings)
                                    Tab.CLOCK -> ClockScreen(openSettings)
                                    Tab.TIMER -> TimerScreen(openSettings)
                                    Tab.STOPWATCH -> StopwatchScreen(openSettings)
                                }
                            }
                            ShortNavigationBar(Modifier.onSizeChanged { barHeight = with(density) { it.height.toDp() } }) {
                                Tab.entries.forEach { item ->
                                    val selected = item == tab
                                    ShortNavigationBarItem(
                                        selected = selected,
                                        onClick = { select(item) },
                                        icon = { Icon(painterResource(if (selected) item.selectedIcon else item.icon), contentDescription = null) },
                                        label = { Text(stringResource(item.label), maxLines = 1) },
                                    )
                                }
                            }
                        }
                    }
                    entry<Screen.Settings> {
                        SettingsRootScreen(
                            onBack = back,
                            onOpenLanguage = { open(Screen.SettingsLanguage) },
                            onOpenBackup = { open(Screen.SettingsBackup) },
                            onOpenReset = { open(Screen.SettingsReset) },
                            onOpenAbout = { open(Screen.SettingsAbout) },
                        )
                    }
                    entry<Screen.SettingsLanguage> { LanguageScreen(onBack = back) }
                    entry<Screen.SettingsBackup> {
                        BackupScreen(onBack = back, snackbars = snackbars, messageScope = scope, onRestored = {
                            // Back on Alarms, with the result (SPEC §3.9.5)
                            while (stack.size > 1) stack.removeAt(stack.lastIndex)
                            select(Tab.ALARMS)
                            showRestored()
                        })
                    }
                    entry<Screen.SettingsReset> { ResetScreen(onBack = back) }
                    entry<Screen.SettingsAbout> {
                        AboutScreen(
                            onBack = back,
                            onOpenWhatsNew = { open(Screen.SettingsWhatsNew) },
                            onOpenLicense = { open(Screen.SettingsLicense) },
                            onOpenLicenses = { open(Screen.SettingsLicenses) },
                        )
                    }
                    entry<Screen.SettingsWhatsNew> { WhatsNewScreen(onBack = back) }
                    entry<Screen.SettingsLicense> { AppLicenseScreen(onBack = back) }
                    entry<Screen.SettingsLicenses> { LicensesScreen(onBack = back) }
                },
            )
            // One host for every page: above the navigation bar on the destinations, above the system's area elsewhere
            val onHome = stack.lastOrNull() == Screen.Home
            val systemBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).padding(bottom = if (onHome) barHeight else systemBar)) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}

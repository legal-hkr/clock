package sh.hnet.clock.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import sh.hnet.clock.R

/** Navigation bar destinations, in the bar's order. */
enum class Tab(@param:StringRes val label: Int, @param:DrawableRes val icon: Int, @param:DrawableRes val selectedIcon: Int) {
    ALARMS(R.string.label_nav_alarms, R.drawable.ic_alarm, R.drawable.ic_alarm_filled),
    CLOCK(R.string.label_nav_clock, R.drawable.ic_schedule, R.drawable.ic_schedule_filled),
    TIMER(R.string.label_nav_timer, R.drawable.ic_hourglass_empty, R.drawable.ic_hourglass_empty_filled),
    STOPWATCH(R.string.label_nav_stopwatch, R.drawable.ic_timer, R.drawable.ic_timer_filled),
}

/** Pages of the one back stack: the destinations (with the navigation bar) and what opens over them. */
@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Home : Screen
    @Serializable data object Settings : Screen
    @Serializable data object SettingsLanguage : Screen
    @Serializable data object SettingsBackup : Screen
    @Serializable data object SettingsReset : Screen
    @Serializable data object SettingsAbout : Screen
    @Serializable data object SettingsWhatsNew : Screen
    @Serializable data object SettingsLicense : Screen
    @Serializable data object SettingsLicenses : Screen
}

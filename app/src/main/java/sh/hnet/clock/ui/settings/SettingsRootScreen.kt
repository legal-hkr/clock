package sh.hnet.clock.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import sh.hnet.clock.ui.clock.ClockViewModel
import sh.hnet.clock.ring.SoundChoice
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.clock.BuildConfig
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import androidx.compose.ui.res.pluralStringResource
import sh.hnet.clock.reliability.Ringing
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.rememberReliability
import sh.hnet.clock.ui.components.SettingsNavigationRow
import sh.hnet.clock.ui.components.SettingsPage

/**
 * The Settings root (SPEC §3.9), a page over the destinations with Back and no navigation bar.
 * Alarms and timers first, then Clock; then, grouped as Music's, App (language), Data (backup, reset) and About on its own.
 * The group for alarms and timers comes before them with its stages.
 */
@Composable
fun SettingsRootScreen(
    clock: ClockViewModel,
    onBack: () -> Unit,
    onOpenClock: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenTimers: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenReset: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ClockApplication
    val version = BuildConfig.VERSION_NAME.removePrefix("v")
    val lastBackup by app.backup.lastBackup.collectAsStateWithLifecycle()
    // Read on every composition: changing it recreates the activity anyway
    val language = appLanguages.firstOrNull { it.tag == currentLanguageTag(context) } ?: appLanguages.first()
    val locale = LocalConfiguration.current.locales[0]
    val zones = remember(locale) { app.zoneNames(locale) }
    val showSeconds by clock.showSeconds.collectAsStateWithLifecycle()
    val homeZoneId by clock.homeZone.collectAsStateWithLifecycle()
    val homeName = remember(homeZoneId, zones) { homeZoneId?.let { zones.city(it).name } }
    SettingsPage(title = stringResource(R.string.title_settings), onBack = onBack) {
        SettingsGroup(stringResource(R.string.title_settings_group_alarms_timers)) {
            row { shapes ->
                val alarmSettings by app.alarms.settings.collectAsStateWithLifecycle()
                SettingsNavigationRow(shapes, R.drawable.ic_alarm, stringResource(R.string.label_nav_alarms), alarmsSummary(alarmSettings), onClick = onOpenAlarms)
            }
            row { shapes ->
                val timerSound by app.preferences.timerSound.collectAsStateWithLifecycle(SoundChoice.Default)
                val summary = stringResource(R.string.label_sound_value, soundName(timerSound))
                SettingsNavigationRow(shapes, R.drawable.ic_hourglass_empty, stringResource(R.string.title_settings_timers), summary, onClick = onOpenTimers)
            }
            row { shapes ->
                // Everything that rings: any enabled alarm with a sound, or the timers' sound
                val alarms by app.alarms.alarms.collectAsStateWithLifecycle()
                val timerSound by app.preferences.timerSound.collectAsStateWithLifecycle(SoundChoice.Default)
                val count = rememberReliability().checks.problems(Ringing.ANY, soundWanted(alarms, timerSound)).size
                SettingsNavigationRow(
                    shapes, R.drawable.ic_verified_user, stringResource(R.string.title_reliability),
                    if (count == 0) stringResource(R.string.msg_reliability_ok) else pluralStringResource(R.plurals.label_problem_count, count, count),
                    badge = if (count > 0) count.toString() else null, error = count > 0, onClick = onOpenReliability,
                )
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_clock)) {
            row { shapes ->
                val seconds = stringResource(if (showSeconds) R.string.label_value_on else R.string.label_value_off)
                val summary = homeName?.let { stringResource(R.string.label_clock_summary, seconds, it) }
                SettingsNavigationRow(shapes, R.drawable.ic_schedule, stringResource(R.string.title_settings_clock), summary, onClick = onOpenClock)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
            row { shapes ->
                val last = lastBackup?.let { stringResource(R.string.label_last_backup, backupDate(context, it)) } ?: stringResource(R.string.label_no_backup)
                SettingsNavigationRow(shapes, R.drawable.ic_settings_backup_restore, stringResource(R.string.title_backup_restore), last, onClick = onOpenBackup)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_restart_alt, stringResource(R.string.title_settings_reset), stringResource(R.string.desc_settings_reset_row), onClick = onOpenReset)
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_info, stringResource(R.string.title_settings_about), stringResource(R.string.label_version_value, version), onClick = onOpenAbout)
            }
        }
    }
}

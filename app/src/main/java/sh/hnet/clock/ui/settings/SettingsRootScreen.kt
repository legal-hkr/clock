package sh.hnet.clock.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.clock.BuildConfig
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.SettingsNavigationRow
import sh.hnet.clock.ui.components.SettingsPage

/**
 * The Settings root (SPEC §3.9), a page over the destinations with Back and no navigation bar.
 * Grouped as Music's: App (language), Data (backup, reset), then About on its own; the groups for
 * alarms, timers and the clock come before them with their stages.
 */
@Composable
fun SettingsRootScreen(
    onBack: () -> Unit,
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
    SettingsPage(title = stringResource(R.string.title_settings), onBack = onBack) {
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

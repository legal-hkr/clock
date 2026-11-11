package sh.hnet.clock.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.ui.clock.ClockViewModel
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.SettingsNavigationRow
import sh.hnet.clock.ui.components.SettingsNote
import sh.hnet.clock.ui.components.SettingsPage
import sh.hnet.clock.ui.components.SettingsSwitchRow

/**
 * Settings › Clock (SPEC §3.9.3): Show seconds; under Traveling the Home clock and the home time
 * zone, chosen on the city search; then where the formats come from, so nobody looks for them here.
 */
@Composable
fun ClockSettingsScreen(viewModel: ClockViewModel, onBack: () -> Unit, onPickHome: () -> Unit) {
    val app = LocalContext.current.applicationContext as ClockApplication
    val locale = LocalConfiguration.current.locales[0]
    val zones = remember(locale) { app.zoneNames(locale) }
    val showSeconds by viewModel.showSeconds.collectAsStateWithLifecycle()
    val homeClock by viewModel.homeClock.collectAsStateWithLifecycle()
    val homeZoneId by viewModel.homeZone.collectAsStateWithLifecycle()
    val home = remember(homeZoneId, zones) { homeZoneId?.let { zones.city(it) } }
    SettingsPage(title = stringResource(R.string.title_settings_clock), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsSwitchRow(
                    shapes, R.drawable.ic_timer_10_alt_1, stringResource(R.string.label_show_seconds),
                    checked = showSeconds, onCheckedChange = { viewModel.setShowSeconds(it) },
                    supporting = stringResource(R.string.desc_show_seconds),
                )
            }
        }
        SettingsGroup(stringResource(R.string.title_traveling)) {
            row { shapes ->
                SettingsSwitchRow(
                    shapes, R.drawable.ic_home, stringResource(R.string.label_home_clock),
                    checked = homeClock, onCheckedChange = { viewModel.setHomeClock(it) },
                    supporting = stringResource(R.string.desc_home_clock),
                )
            }
            row { shapes ->
                SettingsNavigationRow(
                    shapes, R.drawable.ic_public, stringResource(R.string.label_home_zone),
                    supporting = home?.let { stringResource(R.string.label_country_zone, it.name, it.abbreviation) },
                    onClick = onPickHome,
                )
            }
        }
        SettingsNote(stringResource(R.string.msg_formats_note))
    }
}

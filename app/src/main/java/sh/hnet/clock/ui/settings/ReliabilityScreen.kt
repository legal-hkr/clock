@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.alarm.Alarm
import sh.hnet.clock.reliability.Problem
import sh.hnet.clock.reliability.Ringing
import sh.hnet.clock.ring.SoundChoice
import sh.hnet.clock.ui.components.RowIcon
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.SettingsPage
import sh.hnet.clock.ui.components.icon
import sh.hnet.clock.ui.components.open
import sh.hnet.clock.ui.components.rememberProblemFix
import sh.hnet.clock.ui.components.rememberReliability
import sh.hnet.clock.ui.components.settingsRowColors

/** Whether anything that rings wants a sound: an alarm that is on and not silent, or the timers' sound. */
fun soundWanted(alarms: List<Alarm>, timerSound: SoundChoice): Boolean =
    timerSound != SoundChoice.Silent || alarms.any { it.enabled && SoundChoice.decode(it.sound, it.soundTitle) != SoundChoice.Silent }

/**
 * Alarm reliability (SPEC §3.9.4): everything on the phone an alarm depends on, in one place – the
 * verdict on top, then a row for each thing with its state. A row opens the page of the system's
 * settings where that thing is decided; the states are read again on the way back.
 */
@Composable
fun ReliabilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ClockApplication
    val alarms by app.alarms.alarms.collectAsStateWithLifecycle()
    val timerSound by app.preferences.timerSound.collectAsStateWithLifecycle(SoundChoice.Default)
    val reliability = rememberReliability()
    val checks = reliability.checks
    val problems = checks.problems(Ringing.ANY, soundWanted(alarms, timerSound))
    val fix = rememberProblemFix(Ringing.ANY, reliability.refresh)

    SettingsPage(title = stringResource(R.string.title_reliability), onBack = onBack) {
        val wrong = problems.isNotEmpty()
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (wrong) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (wrong) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painterResource(if (wrong) R.drawable.ic_warning else R.drawable.ic_check_circle),
                    contentDescription = null,
                    tint = if (wrong) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (wrong) pluralStringResource(R.plurals.msg_reliability_problems, problems.size, problems.size) else stringResource(R.string.msg_reliability_ok),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        SettingsGroup {
            row { shapes ->
                CheckRow(
                    shapes, if (Problem.NOTIFICATIONS in problems) R.drawable.ic_notifications_off else R.drawable.ic_notifications, stringResource(R.string.label_check_notifications),
                    stringResource(R.string.label_allowed), stringResource(R.string.error_check_notifications), Problem.NOTIFICATIONS in problems,
                ) {
                    // With a problem, its fix; else the page where notifications are decided
                    if (Problem.NOTIFICATIONS in problems) fix(Problem.NOTIFICATIONS)
                    else context.open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
            row { shapes ->
                CheckRow(
                    shapes, Problem.FULL_SCREEN.icon(), stringResource(R.string.label_check_full_screen),
                    stringResource(R.string.label_allowed), stringResource(R.string.error_check_full_screen), Problem.FULL_SCREEN in problems,
                ) { fix(Problem.FULL_SCREEN) }
            }
            row { shapes ->
                CheckRow(
                    shapes, Problem.DO_NOT_DISTURB.icon(), stringResource(R.string.label_check_dnd),
                    stringResource(R.string.label_dnd_ok), stringResource(R.string.error_check_dnd), Problem.DO_NOT_DISTURB in problems,
                ) { fix(Problem.DO_NOT_DISTURB) }
            }
            row { shapes ->
                CheckRow(
                    shapes, Problem.BATTERY.icon(), stringResource(R.string.label_check_battery),
                    stringResource(R.string.label_battery_ok), stringResource(R.string.error_check_battery), Problem.BATTERY in problems,
                ) { fix(Problem.BATTERY) }
            }
            row { shapes ->
                // The volume is the phone's, changed on its sound page (and on Clock's own sound pages)
                CheckRow(
                    shapes, if (checks.volume <= 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up, stringResource(R.string.label_alarm_volume),
                    stringResource(R.string.label_percent, checks.volumePercent), stringResource(R.string.error_volume_zero), Problem.VOLUME in problems,
                ) { context.open(Intent(Settings.ACTION_SOUND_SETTINGS)) }
            }
        }
    }
}

/** One thing alarms depend on: in order, its state in plain words; as a problem, icon box, headline and state in the error roles. */
@Composable
private fun CheckRow(shapes: ListItemShapes, @DrawableRes icon: Int, title: String, fine: String, wrong: String, problem: Boolean, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RowIcon(icon, error = problem) },
        trailingContent = { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null) },
        supportingContent = { Text(if (problem) wrong else fine, color = if (problem) MaterialTheme.colorScheme.error else Color.Unspecified) },
    ) {
        Text(title, color = if (problem) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}

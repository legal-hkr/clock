@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.GRADUAL_CHOICES
import sh.hnet.clock.R
import sh.hnet.clock.alarm.AlarmSettings
import sh.hnet.clock.alarm.VolumeButtons
import sh.hnet.clock.ring.SoundChoice
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.SettingsNavigationRow
import sh.hnet.clock.ui.components.SettingsPage
import sh.hnet.clock.ui.components.SettingsSwitchRow
import sh.hnet.clock.ui.components.SingleChoiceDialog
import sh.hnet.clock.ui.components.durationWords
import sh.hnet.clock.ui.components.groupShapes

@Composable
private fun minutesName(minutes: Int): String = pluralStringResource(R.plurals.label_minutes_count, minutes, minutes)

@Composable
private fun silenceName(minutes: Int): String = if (minutes == 0) stringResource(R.string.label_never) else minutesName(minutes)

@Composable
private fun gradualName(seconds: Int): String = if (seconds == 0) stringResource(R.string.label_off) else durationWords(seconds * 1000L)

@Composable
private fun upcomingName(minutes: Int): String = if (minutes == 0) stringResource(R.string.label_off) else stringResource(R.string.label_before, durationWords(minutes * 60_000L))

/** "Snooze: 10 min, silence after: 10 min", for the Settings root. */
@Composable
fun alarmsSummary(settings: AlarmSettings): String = stringResource(
    R.string.label_alarms_summary,
    durationWords(settings.snoozeMinutes * 60_000L),
    if (settings.silenceAfterMinutes == 0) stringResource(R.string.label_value_never) else durationWords(settings.silenceAfterMinutes * 60_000L),
)

private enum class Choosing { SNOOZE, SILENCE, GRADUAL, UPCOMING }

/**
 * Settings › Alarms (SPEC §3.9.1): how every alarm rings (snooze length, when an unanswered alarm
 * stops, the gradual rise, the volume buttons), what new alarms start with, and the notification
 * before an alarm. The first day of the week has no setting: the system's regional one is used.
 */
@Composable
fun AlarmSettingsScreen(onBack: () -> Unit, onOpenSound: () -> Unit) {
    val controller = (LocalContext.current.applicationContext as ClockApplication).alarms
    val settings by controller.settings.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf<Choosing?>(null) }
    SettingsPage(title = stringResource(R.string.label_nav_alarms), onBack = onBack) {
        SettingsGroup(stringResource(R.string.title_ringing)) {
            row { shapes -> SettingsNavigationRow(shapes, R.drawable.ic_snooze, stringResource(R.string.label_snooze_length), minutesName(settings.snoozeMinutes), onClick = { choosing = Choosing.SNOOZE }) }
            row { shapes -> SettingsNavigationRow(shapes, R.drawable.ic_alarm_off, stringResource(R.string.label_silence_after), silenceName(settings.silenceAfterMinutes), onClick = { choosing = Choosing.SILENCE }) }
            row { shapes -> SettingsNavigationRow(shapes, R.drawable.ic_trending_up, stringResource(R.string.label_gradual_volume), gradualName(settings.gradualSeconds), onClick = { choosing = Choosing.GRADUAL }) }
            row { _ -> VolumeButtonsRow(settings.volumeButtons, groupShapes(3, 4).shape) { choice -> controller.setSettings { it.copy(volumeButtons = choice) } } }
        }
        SettingsGroup(stringResource(R.string.title_new_alarms)) {
            row { shapes -> SettingsNavigationRow(shapes, R.drawable.ic_music_note, stringResource(R.string.label_sound), soundName(SoundChoice.decode(settings.sound, settings.soundTitle)), onClick = onOpenSound) }
            row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_vibration, stringResource(R.string.label_vibration), checked = settings.vibration, onCheckedChange = { on -> controller.setSettings { it.copy(vibration = on) } })
            }
        }
        SettingsGroup(stringResource(R.string.title_before_alarm)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_alarm, stringResource(R.string.label_upcoming_notification), upcomingName(settings.upcomingMinutes), onClick = { choosing = Choosing.UPCOMING })
            }
        }
    }
    val close = { choosing = null }
    val save = stringResource(R.string.button_save)
    when (choosing) {
        Choosing.SNOOZE -> SingleChoiceDialog(
            AnnotatedString(stringResource(R.string.label_snooze_length)), AlarmSettings.SNOOZE_CHOICES, settings.snoozeMinutes, { minutesName(it) }, save,
            onConfirm = { value -> controller.setSettings { it.copy(snoozeMinutes = value) }; close() }, onDismiss = close,
        )
        Choosing.SILENCE -> SingleChoiceDialog(
            AnnotatedString(stringResource(R.string.label_silence_after)), AlarmSettings.SILENCE_CHOICES, settings.silenceAfterMinutes, { silenceName(it) }, save,
            onConfirm = { value -> controller.setSettings { it.copy(silenceAfterMinutes = value) }; close() }, onDismiss = close,
        )
        Choosing.GRADUAL -> SingleChoiceDialog(
            AnnotatedString(stringResource(R.string.label_gradual_volume)), GRADUAL_CHOICES, settings.gradualSeconds, { gradualName(it) }, save,
            onConfirm = { value -> controller.setSettings { it.copy(gradualSeconds = value) }; close() }, onDismiss = close,
        )
        Choosing.UPCOMING -> SingleChoiceDialog(
            AnnotatedString(stringResource(R.string.label_upcoming_notification)), AlarmSettings.UPCOMING_CHOICES, settings.upcomingMinutes, { upcomingName(it) }, save,
            onConfirm = { value -> controller.setSettings { it.copy(upcomingMinutes = value) }; close() }, onDismiss = close,
        )
        null -> Unit
    }
}

/** Three short choices as connected toggle buttons (the system's rule for up to three): Snooze, Stop, Nothing. */
@Composable
private fun VolumeButtonsRow(current: VolumeButtons, shape: androidx.compose.ui.graphics.Shape, onChoose: (VolumeButtons) -> Unit) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.label_volume_buttons), style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                VolumeButtons.entries.forEachIndexed { index, choice ->
                    ToggleButton(
                        checked = current == choice,
                        onCheckedChange = { if (it) onChoose(choice) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            VolumeButtons.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        colors = ToggleButtonDefaults.colors(
                            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    ) {
                        Text(
                            stringResource(
                                when (choice) {
                                    VolumeButtons.SNOOZE -> R.string.button_snooze
                                    VolumeButtons.STOP -> R.string.button_stop
                                    VolumeButtons.NOTHING -> R.string.label_nothing
                                },
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The sound page for one alarm (§3.3.1). */
@Composable
fun AlarmSoundScreen(id: Long, onBack: () -> Unit) {
    val controller = (LocalContext.current.applicationContext as ClockApplication).alarms
    val alarms by controller.alarms.collectAsStateWithLifecycle()
    val alarm = alarms.firstOrNull { it.id == id }
    if (alarm == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    SoundScreen(
        stringResource(R.string.title_alarm_sound), SoundChoice.decode(alarm.sound, alarm.soundTitle),
        onChoose = { choice -> controller.update(id) { it.copy(sound = choice.encode(), soundTitle = (choice as? SoundChoice.Sound)?.title) } },
        onBack = onBack,
    )
}

/** The sound page for new alarms (Settings › Alarms). */
@Composable
fun NewAlarmSoundScreen(onBack: () -> Unit) {
    val controller = (LocalContext.current.applicationContext as ClockApplication).alarms
    val settings by controller.settings.collectAsStateWithLifecycle()
    SoundScreen(
        stringResource(R.string.title_new_alarm_sound), SoundChoice.decode(settings.sound, settings.soundTitle),
        onChoose = { choice -> controller.setSettings { it.copy(sound = choice.encode(), soundTitle = (choice as? SoundChoice.Sound)?.title) } },
        onBack = onBack,
    )
}

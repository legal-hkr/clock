@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.settings

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.GRADUAL_CHOICES
import sh.hnet.clock.R
import sh.hnet.clock.ring.SoundChoice
import sh.hnet.clock.ring.Sounds
import sh.hnet.clock.ui.components.RowIcon
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.SettingsNavigationRow
import sh.hnet.clock.ui.components.SettingsPage
import sh.hnet.clock.ui.components.SettingsSwitchRow
import sh.hnet.clock.ui.components.SingleChoiceDialog
import sh.hnet.clock.ui.components.durationWords
import sh.hnet.clock.ui.components.settingsRowColors

/** A sound choice as a row says it: "Phone’s default (Argon)", "Silent", or the sound's own name. */
@Composable
fun soundName(choice: SoundChoice): String {
    val context = LocalContext.current
    val defaultTitle by produceState<String?>(null) { value = withContext(Dispatchers.IO) { Sounds.defaultTitle(context) } }
    return when (choice) {
        SoundChoice.Default -> defaultTitle?.let { stringResource(R.string.label_phone_default_named, it) } ?: stringResource(R.string.label_phone_default)
        SoundChoice.Silent -> stringResource(R.string.label_silent)
        is SoundChoice.Sound -> choice.title
    }
}

/** "Off", or a length in short words ("30 s", "2 min"). */
@Composable
private fun gradualName(seconds: Int): String = if (seconds == 0) stringResource(R.string.label_off) else durationWords(seconds * 1000L)

/**
 * Settings › Timers (SPEC §3.9.2): what happens when a timer ends – one sound for all of them, the
 * vibration (always on with Silent, §3.3.6), the gradual rise – and a second way to the presets.
 */
@Composable
fun TimerSettingsScreen(onBack: () -> Unit, onOpenSound: () -> Unit, onOpenPresets: () -> Unit) {
    val app = LocalContext.current.applicationContext as ClockApplication
    val scope = rememberCoroutineScope()
    val sound by app.preferences.timerSound.collectAsStateWithLifecycle(SoundChoice.Default)
    val vibration by app.preferences.timerVibration.collectAsStateWithLifecycle(true)
    val gradual by app.preferences.timerGradual.collectAsStateWithLifecycle(0)
    val presets by app.timers.presets.collectAsStateWithLifecycle()
    var choosingGradual by rememberSaveable { mutableStateOf(false) }
    val silent = sound == SoundChoice.Silent
    SettingsPage(title = stringResource(R.string.title_settings_timers), onBack = onBack) {
        SettingsGroup(stringResource(R.string.title_when_timer_ends)) {
            row { shapes -> SettingsNavigationRow(shapes, R.drawable.ic_music_note, stringResource(R.string.label_sound), soundName(sound), onClick = onOpenSound) }
            row { shapes ->
                // A silent timer without vibration would be no timer: the switch is on and locked
                SegmentedListItem(
                    checked = vibration || silent,
                    onCheckedChange = { on -> scope.launch { app.preferences.setTimerVibration(on) } },
                    enabled = !silent,
                    shapes = shapes,
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_vibration) },
                    trailingContent = { Switch(checked = vibration || silent, onCheckedChange = null, enabled = !silent) },
                    supportingContent = if (silent) ({ Text(stringResource(R.string.desc_vibration_locked)) }) else null,
                ) {
                    Text(stringResource(R.string.label_vibration))
                }
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_trending_up, stringResource(R.string.label_gradual_volume), gradualName(gradual), onClick = { choosingGradual = true })
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(
                    shapes, R.drawable.ic_bookmarks, stringResource(R.string.label_presets),
                    pluralStringResource(R.plurals.label_preset_count, presets.size, presets.size), onClick = onOpenPresets,
                )
            }
        }
    }
    if (choosingGradual) {
        SingleChoiceDialog(
            title = AnnotatedString(stringResource(R.string.label_gradual_volume)),
            options = GRADUAL_CHOICES,
            initial = gradual,
            label = { gradualName(it) },
            confirmLabel = stringResource(R.string.button_save),
            onConfirm = { seconds ->
                choosingGradual = false
                scope.launch { app.preferences.setTimerGradual(seconds) }
            },
            onDismiss = { choosingGradual = false },
        )
    }
}

/** The sound page for timers: one sound for all of them. */
@Composable
fun TimerSoundScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ClockApplication
    val scope = rememberCoroutineScope()
    val sound by app.preferences.timerSound.collectAsStateWithLifecycle(SoundChoice.Default)
    SoundScreen(stringResource(R.string.title_timer_sound), sound, onChoose = { scope.launch { app.preferences.setTimerSound(it) } }, onBack = onBack)
}

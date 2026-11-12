@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.settings

import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.clock.R
import sh.hnet.clock.ring.Ringer
import sh.hnet.clock.ring.SoundChoice
import sh.hnet.clock.ring.Sounds
import sh.hnet.clock.ring.SystemSound
import sh.hnet.clock.ui.components.LazySettingsPage
import sh.hnet.clock.ui.components.RowIcon
import sh.hnet.clock.ui.components.boldNamesResource
import sh.hnet.clock.ui.components.SETTINGS_GROUP_GAP
import sh.hnet.clock.ui.components.SETTINGS_ROW_GAP
import sh.hnet.clock.ui.components.SettingsGroupHeader
import sh.hnet.clock.ui.components.groupShapes
import sh.hnet.clock.ui.components.settingsRowColors
import kotlin.math.roundToInt

/**
 * The sound page (SPEC §3.3), one for every place a sound is chosen: the phone's alarm volume, then
 * Phone's default and Silent, then every alarm sound on the phone, A to Z. A tap chooses, saves at
 * once and plays the sound on the alarm stream at its volume; a second tap, leaving the page or
 * putting the app away stops it.
 */
@Composable
fun SoundScreen(title: String, current: SoundChoice, onChoose: (SoundChoice) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val sounds by produceState<List<SystemSound>?>(null) { value = withContext(Dispatchers.IO) { Sounds.list(context) } }
    val defaultTitle by produceState<String?>(null) { value = withContext(Dispatchers.IO) { Sounds.defaultTitle(context) } }
    // A sound that is gone rings as the phone's default, and shows as it until another is chosen (§3.3.5)
    val gone = (current as? SoundChoice.Sound)?.takeIf { Sounds.gone(it, sounds?.takeIf { list -> list.isNotEmpty() }?.associate { sound -> sound.uri to sound.title }) }
    val shown = if (gone != null) SoundChoice.Default else current
    val ringer = remember { Ringer(context) }
    // What is being played: an encoded choice, null when quiet
    var playing by remember { mutableStateOf<String?>(null) }
    val stop = {
        ringer.stop()
        playing = null
    }
    LifecycleResumeEffect(ringer) { onPauseOrDispose { stop() } }
    DisposableEffect(ringer) { onDispose { ringer.stop() } }

    val choose: (SoundChoice) -> Unit = { choice ->
        val key = choice.encode()
        if (current != choice) onChoose(choice)
        when {
            playing == key -> stop()
            choice == SoundChoice.Silent -> {
                stop()
                ringer.pulse()
            }
            else -> {
                // Looping, at the alarm volume as it is, without the gradual rise (§3.3.3)
                ringer.start(Sounds.uris(context, choice).take(1), vibrate = false, wake = false)
                playing = if (ringer.playing) key else null
            }
        }
    }

    LazySettingsPage(title = title, onBack = onBack, state = rememberLazyListState()) {
        gone?.let { sound ->
            item(key = "gone") {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = SETTINGS_GROUP_GAP)) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_music_off), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(boldNamesResource(R.string.msg_sound_gone_card, sound.title), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item(key = "volume") { AlarmVolume(Modifier.padding(bottom = SETTINGS_GROUP_GAP)) }
        item(key = "fixed") {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(SETTINGS_ROW_GAP)) {
                SoundRow(
                    groupShapes(0, 2), stringResource(R.string.label_phone_default), defaultTitle,
                    selected = shown == SoundChoice.Default, playing = playing == SoundChoice.Default.encode(),
                ) { choose(SoundChoice.Default) }
                SoundRow(
                    groupShapes(1, 2), stringResource(R.string.label_silent), stringResource(R.string.desc_silent),
                    selected = shown == SoundChoice.Silent, playing = false,
                ) { choose(SoundChoice.Silent) }
            }
        }
        val list = sounds.orEmpty()
        if (list.isNotEmpty()) {
            item(key = "header") {
                Column(Modifier.padding(top = SETTINGS_GROUP_GAP)) { SettingsGroupHeader(stringResource(R.string.title_sounds_on_phone)) }
            }
            itemsIndexed(list, key = { _, sound -> sound.uri }, contentType = { _, _ -> "sound" }) { position, sound ->
                val choice = SoundChoice.Sound(sound.uri, sound.title)
                Column(Modifier.padding(bottom = if (position < list.lastIndex) SETTINGS_ROW_GAP else 0.dp)) {
                    SoundRow(
                        groupShapes(position, list.size), sound.title, null,
                        selected = (shown as? SoundChoice.Sound)?.uri == sound.uri, playing = playing == sound.uri,
                    ) { choose(choice) }
                }
            }
        }
    }
}

/** The phone's alarm sounds as address to title; null until read, and when the list can't be read – then nothing is called gone. */
@Composable
fun rememberSystemSounds(): Map<String, String>? {
    val context = LocalContext.current
    val sounds by produceState<Map<String, String>?>(null) {
        value = withContext(Dispatchers.IO) { Sounds.list(context) }.associate { it.uri to it.title }.ifEmpty { null }
    }
    return sounds
}

/** The note for a sound that is gone (§3.2.8 #7): "Argon is gone. Rings with the phone's default (Helium)."; null for one that is there. */
@Composable
fun soundGoneText(choice: SoundChoice, sounds: Map<String, String>?): String? {
    if (choice !is SoundChoice.Sound || !Sounds.gone(choice, sounds)) return null
    val context = LocalContext.current
    val defaultTitle by produceState<String?>(null) { value = withContext(Dispatchers.IO) { Sounds.defaultTitle(context) } }
    return defaultTitle?.let { stringResource(R.string.msg_sound_gone, choice.title, it) } ?: stringResource(R.string.msg_sound_gone_unnamed, choice.title)
}

/** A choice of the list: radio button, the sound's name, and "Playing" with the equalizer mark while it is heard. */
@Composable
private fun SoundRow(shapes: ListItemShapes, title: String, supporting: String?, selected: Boolean, playing: Boolean, onClick: () -> Unit) {
    val line = if (playing) stringResource(R.string.label_playing) else supporting
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = if (playing) ({ Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null) }) else null,
        supportingContent = line?.let { { Text(it) } },
    ) {
        Text(title)
    }
}

/**
 * The phone's alarm volume (§3.3.4): the system's own alarm stream, here because this is where a
 * sound can be heard against it. It follows changes made elsewhere (the volume buttons).
 */
@Composable
private fun AlarmVolume(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val min = remember { audio.getStreamMinVolume(AudioManager.STREAM_ALARM) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_ALARM)) }
    DisposableEffect(audio) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                volume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val zero = volume == 0
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RowIcon(if (zero) R.drawable.ic_volume_off else R.drawable.ic_volume_up, error = zero)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.label_alarm_volume), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(if (zero) R.string.error_volume_zero else R.string.desc_alarm_volume),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (zero) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (max > min) {
                    val slider = rememberSliderState(volume.toFloat(), (max - min - 1).coerceAtLeast(0), min.toFloat()..max.toFloat())
                    // The slider follows the volume wherever it was changed
                    LaunchedEffect(volume) {
                        if (slider.value.roundToInt() != volume) slider.value = volume.toFloat()
                    }
                    Slider(
                        state = slider,
                        onValueChange = { value ->
                            val next = value.roundToInt().coerceIn(min, max)
                            if (next != volume) {
                                volume = next
                                audio.setStreamVolume(AudioManager.STREAM_ALARM, next, 0)
                            }
                        },
                    )
                }
            }
        }
    }
}

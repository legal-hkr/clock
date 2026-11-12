package sh.hnet.clock.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import sh.hnet.clock.R
import sh.hnet.clock.timer.Timer
import sh.hnet.clock.timer.TimerPreset
import sh.hnet.clock.timer.TimerFormat

/** A duration in short words: "3 min", "1 h 30 min", "45 s", "1 min 30 s". */
fun durationWords(resources: Resources, duration: Long): String {
    val (h, m, s) = TimerFormat.parts(duration)
    return listOfNotNull(
        if (h > 0) resources.getString(R.string.label_duration_h, h) else null,
        if (m > 0) resources.getString(R.string.label_duration_min, m) else null,
        if (s > 0 || (h == 0 && m == 0)) resources.getString(R.string.label_duration_s, s) else null,
    ).joinToString(" ")
}

/**
 * What a timer is called: its label, or – without one – the duration it was set to, "Timer (15 min)"
 * (handoff of 12 Nov 2026, §3.7). Adding a minute or resetting doesn't change that name.
 */
fun timerName(resources: Resources, timer: Timer): String =
    timer.label.ifEmpty { resources.getString(R.string.label_timer_unnamed, durationWords(resources, timer.original)) }

/** A preset as one text: "Tea (3 min)", or its duration alone when it has no name. */
fun presetTitle(resources: Resources, preset: TimerPreset): String {
    val duration = durationWords(resources, preset.duration)
    return if (preset.name.isEmpty()) duration else resources.getString(R.string.label_days_and_label, preset.name, duration)
}

@Composable
fun timerName(timer: Timer): String = timerName(LocalResources.current, timer)

@Composable
fun durationWords(duration: Long): String = durationWords(LocalResources.current, duration)

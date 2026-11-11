package sh.hnet.clock.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import sh.hnet.clock.R
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

@Composable
fun durationWords(duration: Long): String = durationWords(LocalResources.current, duration)

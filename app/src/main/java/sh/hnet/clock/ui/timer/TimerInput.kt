@file:OptIn(ExperimentalFoundationApi::class)

package sh.hnet.clock.ui.timer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import sh.hnet.clock.R
import sh.hnet.clock.timer.TimerInput
import sh.hnet.clock.timer.TimerPreset
import sh.hnet.clock.ui.components.durationWords
import sh.hnet.clock.ui.components.tabular

/**
 * The presets as tonal chips (SPEC §3.6.2): one tap starts that timer with the preset's name. The
 * last chip opens the presets page – "Edit", or "Add preset" while there are none. A long name is
 * cut in the chip, its duration never: "Tea (3 min)".
 */
@Composable
fun PresetChips(presets: List<TimerPreset>, onStart: (TimerPreset) -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(presets, key = { it.id }) { preset ->
            val duration = durationWords(preset.duration)
            SuggestionChip(
                onClick = { onStart(preset) },
                label = {
                    Row {
                        Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text(" ($duration)", maxLines = 1)
                    }
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                border = null,
                modifier = Modifier.widthIn(max = 240.dp),
            )
        }
        item(key = "edit") {
            AssistChip(
                onClick = onEdit,
                label = { Text(stringResource(if (presets.isEmpty()) R.string.button_add_preset else R.string.button_edit)) },
                leadingIcon = {
                    Icon(painterResource(if (presets.isEmpty()) R.drawable.ic_add else R.drawable.ic_edit), contentDescription = null, modifier = Modifier.height(AssistChipDefaults.IconSize))
                },
            )
        }
    }
}

/**
 * The duration being typed and its keypad (§3.6.1): "00h 05m 30s" filling from the right, the
 * places not yet typed muted; 1–9, "00", 0 and backspace, which clears everything when held.
 */
@Composable
fun DurationKeypad(input: TimerInput, onInput: (TimerInput) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        DurationDisplay(input)
        Column(Modifier.widthIn(max = 320.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    keys.forEach { key -> Key(key, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)) { onInput(input.type(key)) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Key("00", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Modifier.weight(1f)) { onInput(input.type("00")) }
                Key("0", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)) { onInput(input.type("0")) }
                val backspace = stringResource(R.string.content_description_backspace)
                Box(
                    Modifier
                        .weight(1f)
                        .height(KEY_HEIGHT)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .combinedClickable(
                            onClick = { onInput(input.backspace()) },
                            onLongClick = { onInput(TimerInput()) },
                            onLongClickLabel = stringResource(R.string.label_clear),
                        )
                        .semantics { role = Role.Button; contentDescription = backspace },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_backspace), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, container: Color, content: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(KEY_HEIGHT).clip(CircleShape).background(container).combinedClickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall, color = content)
    }
}

/** "00h 05m 30s": digits in display-medium, units in title-large; read as a duration in words. */
@Composable
private fun DurationDisplay(input: TimerInput) {
    val spoken = durationWords(input.duration)
    val typed = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.clearAndSetSemantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(input.hours to R.string.label_unit_h, input.minutes to R.string.label_unit_m, input.seconds to R.string.label_unit_s).forEachIndexed { index, (digits, unit) ->
            Row {
                Text(
                    buildAnnotatedString {
                        digits.forEachIndexed { place, digit ->
                            // Places are counted over all six digits, from the left
                            withStyle(SpanStyle(color = if (index * 2 + place < input.untyped) muted else typed)) { append(digit) }
                        }
                    },
                    style = MaterialTheme.typography.displayMedium.tabular,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    stringResource(unit),
                    style = MaterialTheme.typography.titleLarge,
                    color = if ((index + 1) * 2 <= input.untyped) muted else typed,
                    modifier = Modifier.alignByBaseline().padding(start = 2.dp),
                )
            }
        }
    }
}

private val KEY_HEIGHT = 64.dp

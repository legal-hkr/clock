@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.clock.ui.alarms

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import sh.hnet.clock.R

/**
 * The time of an alarm (SPEC §3.2.2): Material's time picker in its standard dialog – the dial
 * first, the keyboard toggle switching to two number fields, the mode used last remembered; 12- or
 * 24-hour by the system. Cancel, Save.
 */
@Composable
fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onSave: (hour: Int, minute: Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = DateFormat.is24HourFormat(context))
    var input by rememberSaveable { mutableStateOf(store(context).getBoolean(KEY_INPUT, false)) }
    val mode = if (input) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(state.hour, state.minute) }) { Text(stringResource(R.string.button_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        title = { TimePickerDialogDefaults.Title(displayMode = mode) },
        modeToggleButton = {
            TimePickerDialogDefaults.DisplayModeToggle(
                onDisplayModeChange = {
                    input = !input
                    store(context).edit { putBoolean(KEY_INPUT, input) }
                },
                displayMode = mode,
            )
        },
    ) {
        if (input) TimeInput(state) else TimePicker(state)
    }
}

private fun store(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
private const val KEY_INPUT = "time_picker_input"

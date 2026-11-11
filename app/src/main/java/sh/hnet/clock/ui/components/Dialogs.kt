package sh.hnet.clock.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import sh.hnet.clock.R

/**
 * Confirmation of something that can't be undone: the icon in the error color, Cancel, then the
 * verb in the error color, last. [onConfirm] does the work and closes the dialog.
 */
@Composable
fun ConfirmDestructiveDialog(
    @DrawableRes icon: Int,
    title: AnnotatedString,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    text: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = text,
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(confirmLabel)
            }
        },
    )
}

/** [ConfirmDestructiveDialog] with a plain title and one paragraph of text. */
@Composable
fun ConfirmDestructiveDialog(
    @DrawableRes icon: Int,
    title: String,
    text: AnnotatedString,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = ConfirmDestructiveDialog(icon, AnnotatedString(title), confirmLabel, onConfirm, onDismiss) { Text(text) }

/**
 * A page's single destructive action (Restore defaults): a full-width outlined button in the error
 * color (owner's decision, as in Music); it opens a [ConfirmDestructiveDialog] or offers Undo in a
 * snackbar.
 */
@Composable
fun DestructiveButton(text: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = modifier.fillMaxWidth(),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * One choice among [options]: radio rows under an optional
 * [description], Cancel and [confirmLabel]; the choice is kept across rotation.
 */
@Composable
fun <T> SingleChoiceDialog(
    title: AnnotatedString,
    options: List<T>,
    initial: T,
    label: @Composable (T) -> String,
    confirmLabel: String,
    onConfirm: (T) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
) {
    var chosen by rememberSaveable { mutableIntStateOf(options.indexOf(initial).coerceAtLeast(0)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                description?.let { Text(it, modifier = Modifier.padding(bottom = 8.dp)) }
                Column(Modifier.selectableGroup()) {
                    options.forEachIndexed { index, option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = chosen == index, role = Role.RadioButton) { chosen = index }
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == index, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(label(option), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { TextButton(onClick = { onConfirm(options[chosen]) }) { Text(confirmLabel) } },
    )
}

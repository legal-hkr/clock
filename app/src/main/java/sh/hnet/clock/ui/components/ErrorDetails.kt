package sh.hnet.clock.ui.components

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.clock.R

/** An error's sentence and its details, for the Show dialog. */
class ErrorDetails(val message: String, val details: String)

/** Shows an error snackbar with Show, which opens its details. */
fun CoroutineScope.reportFailure(snackbars: SnackbarHostState, message: String, details: String, show: String, onShow: (ErrorDetails) -> Unit) = launch {
    snackbars.currentSnackbarData?.dismiss()
    if (snackbars.showSnackbar(message, actionLabel = show, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
        onShow(ErrorDetails(message, details))
    }
}

/** The error-details dialog: the sentence, the details (selectable), Copy and Close. Details are shown, never logged. */
@Composable
fun ErrorDetailsDialog(error: ErrorDetails, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val copied = stringResource(R.string.msg_copied)
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(error.message) },
        text = {
            SelectionContainer(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                Text(error.details, style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, error.message + "\n" + error.details)))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.button_copy)) }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_close)) } },
    )
}

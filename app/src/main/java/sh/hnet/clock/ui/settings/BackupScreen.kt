@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import sh.hnet.clock.ui.components.ConfirmDestructiveDialog
import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.backup.BackupCheck
import sh.hnet.clock.backup.BackupManager
import sh.hnet.clock.ui.components.RowIcon
import sh.hnet.clock.ui.components.SettingsDescription
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.SettingsPage
import sh.hnet.clock.ui.components.boldNamesResource
import sh.hnet.clock.ui.components.settingsRowColors
import sh.hnet.clock.ui.components.ErrorDetails
import sh.hnet.clock.ui.components.ErrorDetailsDialog
import sh.hnet.clock.ui.components.reportFailure
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The picker's types for a backup: JSON plus what files often arrive as. */
val BACKUP_MIME_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

/** "clock_backup_20261111_142005.json", as ComfyChair and Music name their backups. */
fun backupFileName(): String = "clock_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"

/** "11 Nov 2026, 14:20" in the app's language. */
fun backupDate(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
)

/** A backup's ISO date as [backupDate], or as written when it can't be read. */
fun backupDate(context: Context, iso: String): String =
    runCatching { backupDate(context, java.time.Instant.parse(iso).toEpochMilli()) }.getOrDefault(iso)

/**
 * Settings › Backup and restore (SPEC §3.9.5, Music's page): what a backup holds, Create backup and
 * Restore from backup. Restore checks the picked file, then asks; errors come as a snackbar with Show.
 */
@Composable
fun BackupScreen(onBack: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope, onRestored: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ClockApplication
    val lastBackup by app.backup.lastBackup.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf(false) }
    var errorShown by remember { mutableStateOf<ErrorDetails?>(null) }
    var pending by remember { mutableStateOf<BackupManager.Picked?>(null) }
    val failures = rememberBackupFailures()
    val savedText = stringResource(R.string.msg_backup_saved)
    val writeError = stringResource(R.string.error_backup_write)
    val show = stringResource(R.string.button_show)

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        messageScope.launch {
            // The row shows Saving… only when it takes a moment
            val indicator = launch { delay(300); saving = true }
            val result = app.backup.create(uri)
            indicator.cancel()
            saving = false
            when (result) {
                is BackupManager.Created.Saved -> {
                    snackbars.currentSnackbarData?.dismiss()
                    snackbars.showSnackbar(AnnotatedString.fromHtml(savedText.format(result.fileName.htmlEncode())).text, duration = SnackbarDuration.Short)
                }
                is BackupManager.Created.Failed -> messageScope.reportFailure(
                    snackbars, AnnotatedString.fromHtml(writeError.format(result.fileName.htmlEncode())).text, result.details, show,
                ) { errorShown = it }
            }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        messageScope.launch {
            val picked = app.backup.read(uri)
            if (picked.check is BackupCheck.Valid) pending = picked
            else failures.report(picked)?.let { (message, details) -> messageScope.reportFailure(snackbars, message, details, show) { errorShown = it } }
        }
    }

    SettingsPage(title = stringResource(R.string.title_backup_restore), onBack = onBack) {
        SettingsDescription(stringResource(R.string.msg_backup_intro))
        SettingsGroup {
            row { shapes ->
                CreateBackupRow(shapes, saving, lastBackup?.let { stringResource(R.string.label_last_backup, backupDate(context, it)) } ?: stringResource(R.string.label_no_backup)) {
                    create.launch(backupFileName())
                }
            }
            row { shapes ->
                SegmentedListItem(
                    onClick = { restore.launch(BACKUP_MIME_TYPES) },
                    shapes = shapes,
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_settings_backup_restore) },
                    supportingContent = { Text(stringResource(R.string.desc_restore_backup)) },
                ) {
                    Text(stringResource(R.string.label_restore_backup))
                }
            }
        }
    }

    pending?.let { picked ->
        RestoreConfirmDialog(
            picked = picked,
            onDismiss = { pending = null },
            onConfirm = { valid ->
                pending = null
                messageScope.launch {
                    app.backup.restore(valid.backup)
                    onRestored()
                }
            },
        )
    }
    errorShown?.let { ErrorDetailsDialog(it) { errorShown = null } }
}

@Composable
private fun CreateBackupRow(shapes: ListItemShapes, saving: Boolean, supporting: String, onClick: () -> Unit) {
    val savingText = stringResource(R.string.label_saving_backup)
    SegmentedListItem(
        onClick = onClick,
        enabled = !saving,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            if (saving) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(40.dp)) }
            else RowIcon(R.drawable.ic_save)
        },
        supportingContent = { Text(if (saving) savingText else supporting) },
        modifier = Modifier.semantics { if (saving) stateDescription = savingText },
    ) {
        Text(stringResource(R.string.label_create_backup))
    }
}

/** Turns a refused or unreadable file into the snackbar's sentence and the dialog's details. */
class BackupFailures internal constructor(private val notOurs: String, private val newer: String, private val unreadable: String) {
    fun report(picked: BackupManager.Picked): Pair<String, String>? {
        val name = picked.fileName.htmlEncode()
        return when (val check = picked.check) {
            null -> AnnotatedString.fromHtml(unreadable.format(name)).text to (picked.details ?: "")
            is BackupCheck.NotOurs -> AnnotatedString.fromHtml(notOurs.format(name)).text to check.details
            is BackupCheck.Newer -> newer to "formatVersion ${check.formatVersion}"
            is BackupCheck.Valid -> null
        }
    }
}

@Composable
fun rememberBackupFailures(): BackupFailures {
    val notOurs = stringResource(R.string.error_backup_not_ours)
    val newer = stringResource(R.string.error_backup_newer)
    val unreadable = stringResource(R.string.error_backup_unreadable)
    return remember(notOurs, newer, unreadable) { BackupFailures(notOurs, newer, unreadable) }
}

/**
 * Restore's confirmation: destructive, with what the file brings and what it replaces; Replace in
 * the error color, last. The list grows with the stages (alarms, timer presets).
 */
@Composable
private fun RestoreConfirmDialog(picked: BackupManager.Picked, onDismiss: () -> Unit, onConfirm: (BackupCheck.Valid) -> Unit) {
    val valid = picked.check as? BackupCheck.Valid ?: return
    val context = LocalContext.current
    val backup = valid.backup
    ConfirmDestructiveDialog(
        icon = R.drawable.ic_settings_backup_restore,
        title = AnnotatedString(stringResource(R.string.title_restore_confirm)),
        confirmLabel = stringResource(R.string.button_replace),
        onConfirm = { onConfirm(valid) },
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(boldNamesResource(R.string.msg_restore_from, picked.fileName, backupDate(context, backup.createdAt), backup.appVersion))
            listOfNotNull(
                stringResource(R.string.msg_restore_settings),
                // Absent in a backup from before cities, which then stay as they are
                valid.backup.cities?.let { stringResource(R.string.msg_restore_cities, it.size) },
                valid.backup.timerPresets?.let { stringResource(R.string.msg_restore_presets, it.size) },
            ).forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("–")
                    Text(item)
                }
            }
            Text(stringResource(R.string.msg_restore_replaces))
        }
    }
}

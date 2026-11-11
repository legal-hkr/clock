@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.welcome

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.backup.BackupCheck
import sh.hnet.clock.backup.BackupManager
import sh.hnet.clock.ui.components.AppLogo
import sh.hnet.clock.ui.components.FormPage
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.settingsRowColors
import sh.hnet.clock.ui.settings.BACKUP_MIME_TYPES
import sh.hnet.clock.ui.settings.backupDate

/** The wizard's pages; the welcome page first. */
enum class WizardPage { WELCOME, RESTORE }

/** What the user chose on the welcome page. */
enum class WelcomeChoice { START, RESTORE }

object FirstRun {
    private const val STORE = "first_run"
    private const val DONE = "welcome_done"

    /**
     * Whether the wizard is still to be shown. Unlike Music, whose wizard ends by adding a folder,
     * Clock's can end with nothing stored at all, so it leaves a mark of its own (kept apart from
     * the settings: Restore defaults doesn't bring the wizard back).
     */
    fun isNeeded(context: Context): Boolean = !context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getBoolean(DONE, false)

    fun done(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit { putBoolean(DONE, true) }
}

private val pagesSaver = listSaver<List<WizardPage>, String>(
    save = { pages -> pages.map { it.name } },
    restore = { names -> names.map { WizardPage.valueOf(it) } },
)

/**
 * The first-run wizard (as ComfyChair's and Music's): a list of pages, pushed forward and popped
 * back (with the predictive back gesture; Back on the welcome page leaves the app). It ends with
 * Start fresh or with a restore ([onDone], true after a restore). Permissions are not asked here
 * but when the first alarm or timer needs them (SPEC §3.11.2).
 */
@Composable
fun WelcomeWizard(onDone: (restored: Boolean) -> Unit) {
    var pages by rememberSaveable(stateSaver = pagesSaver) { mutableStateOf(listOf(WizardPage.WELCOME)) }
    var choice by rememberSaveable { mutableStateOf<WelcomeChoice?>(null) }

    var goingBack by remember { mutableStateOf(false) }
    val transitionState = remember { SeekableTransitionState(pages.last()) }
    val transition = rememberTransition(transitionState, label = "wizard")
    val scope = rememberCoroutineScope()
    LaunchedEffect(pages.last()) { transitionState.animateTo(pages.last()) }

    fun push(page: WizardPage) {
        goingBack = false
        pages = pages + page
    }

    fun pop() {
        goingBack = true
        pages = pages.dropLast(1)
    }

    PredictiveBackHandler(enabled = pages.size > 1) { progress ->
        val previous = pages[pages.size - 2]
        goingBack = true
        try {
            progress.collect { event -> transitionState.seekTo(event.progress, targetState = previous) }
            pages = pages.dropLast(1)
        } catch (e: CancellationException) {
            scope.launch { transitionState.animateTo(pages.last()) }
            throw e
        }
    }

    val motion = MaterialTheme.motionScheme
    val axisOffsetPx = with(LocalDensity.current) { 30.dp.roundToPx() }
    transition.AnimatedContent(
        transitionSpec = {
            val back = goingBack
            (slideInHorizontally(motion.defaultSpatialSpec()) { if (back) -axisOffsetPx else axisOffsetPx } + fadeIn(motion.defaultEffectsSpec()))
                .togetherWith(slideOutHorizontally(motion.defaultSpatialSpec()) { if (back) axisOffsetPx else -axisOffsetPx } + fadeOut(motion.fastEffectsSpec()))
                .using(SizeTransform(clip = false))
        },
    ) { page ->
        when (page) {
            WizardPage.WELCOME -> WelcomePage(
                choice = choice,
                onChoose = { choice = it },
                onNext = {
                    when (choice) {
                        WelcomeChoice.START -> onDone(false)
                        WelcomeChoice.RESTORE -> push(WizardPage.RESTORE)
                        null -> Unit
                    }
                },
            )
            WizardPage.RESTORE -> RestorePage(onBack = ::pop, onRestored = { onDone(true) })
        }
    }
}

@Composable
private fun WelcomePage(choice: WelcomeChoice?, onChoose: (WelcomeChoice) -> Unit, onNext: () -> Unit) {
    FormPage(
        title = null,
        confirmLabel = stringResource(R.string.button_next),
        onConfirm = onNext,
        confirmEnabled = choice != null,
        contentSpacing = 0.dp,
    ) {
        Spacer(Modifier.height(16.dp))
        AppLogo(titleInset = TITLE_INSET)
        Text(
            stringResource(R.string.title_welcome_wizard),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = TITLE_INSET).semantics { heading() },
        )
        Text(
            stringResource(R.string.msg_welcome),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 12.dp),
        )
        Text(
            stringResource(R.string.label_welcome_question),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 32.dp, bottom = 12.dp).semantics { heading() },
        )
        // One choice, nothing preselected
        Column(Modifier.selectableGroup()) {
            SettingsGroup {
                row { shapes ->
                    ChoiceRow(shapes, R.string.label_welcome_start, R.string.desc_welcome_start, R.drawable.ic_alarm, choice == WelcomeChoice.START) {
                        onChoose(WelcomeChoice.START)
                    }
                }
                row { shapes ->
                    ChoiceRow(shapes, R.string.label_welcome_restore, R.string.desc_welcome_restore, R.drawable.ic_settings_backup_restore, choice == WelcomeChoice.RESTORE) {
                        onChoose(WelcomeChoice.RESTORE)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A radio row with its icon at the end, as the rows of ComfyChair's welcome page. */
@Composable
private fun ChoiceRow(shapes: androidx.compose.material3.ListItemShapes, title: Int, supporting: Int, @DrawableRes icon: Int, selected: Boolean, onSelect: () -> Unit) {
    SegmentedListItem(
        selected = selected,
        onClick = onSelect,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = { Icon(painterResource(icon), contentDescription = null) },
        supportingContent = { Text(stringResource(supporting)) },
    ) {
        Text(stringResource(title))
    }
}

/** Where the restore page is: nothing picked, a checked file ready, or a refused file. */
private sealed interface RestoreStep {
    data object NoFile : RestoreStep
    data class Ready(val picked: BackupManager.Picked, val valid: BackupCheck.Valid) : RestoreStep
    data class Refused(val picked: BackupManager.Picked) : RestoreStep
}

/**
 * Restoring a backup (as ComfyChair's and Music's restore page): what comes back, the picked file
 * with what it holds – on a first run there is nothing to replace, so the file card is the
 * confirmation – then Restore. While reading or restoring, the button is busy and Back waits. A
 * refused file shows on the page; another can be picked.
 */
@Composable
private fun RestorePage(onBack: () -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ClockApplication
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<RestoreStep>(RestoreStep.NoFile) }
    var busy by remember { mutableStateOf(false) }
    val unnamed = stringResource(R.string.label_backup_unnamed_file)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val picked = app.backup.read(uri).let { if (it.fileName.isBlank()) it.copy(fileName = unnamed) else it }
            val check = picked.check
            step = if (check is BackupCheck.Valid) RestoreStep.Ready(picked, check) else RestoreStep.Refused(picked)
            busy = false
        }
    }
    val pick = { picker.launch(BACKUP_MIME_TYPES) }

    // Back waits while the file is read or restored (registered after the wizard's handler, so it comes first)
    BackHandler(enabled = busy) {}

    val ready = step as? RestoreStep.Ready
    FormPage(
        title = stringResource(R.string.label_welcome_restore),
        onBack = onBack,
        confirmLabel = stringResource(
            when (step) {
                RestoreStep.NoFile -> R.string.button_backup_choose_file
                is RestoreStep.Ready -> R.string.button_restore
                is RestoreStep.Refused -> R.string.button_backup_choose_another
            },
        ),
        confirmIcon = if (ready != null) R.drawable.ic_settings_backup_restore else R.drawable.ic_folder_open,
        busy = busy,
        contentSpacing = 16.dp,
        onConfirm = {
            if (ready == null) {
                pick()
            } else {
                busy = true
                scope.launch {
                    app.backup.restore(ready.valid.backup)
                    busy = false
                    onRestored()
                }
            }
        },
    ) {
        Text(stringResource(R.string.label_backup_brings_back), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BackupContentsCard()
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = step,
            contentKey = { it::class },
            transitionSpec = { (fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec())).togetherWith(fadeOut(motion.fastEffectsSpec())) },
        ) { shown ->
            when (shown) {
                RestoreStep.NoFile -> Unit
                is RestoreStep.Ready -> BackupFileCard(shown.picked.fileName, shown.valid, enabled = !busy, onChange = pick)
                is RestoreStep.Refused -> {
                    val (title, body) = when (shown.picked.check) {
                        is BackupCheck.Newer -> R.string.error_backup_newer_title to R.string.error_backup_newer_body
                        is BackupCheck.NotOurs -> R.string.error_backup_not_ours_title to R.string.error_backup_not_ours_body
                        else -> R.string.error_backup_unreadable_title to R.string.error_backup_unreadable_body
                    }
                    RestoreErrorCard(stringResource(title), stringResource(body, shown.picked.fileName))
                }
            }
        }
    }
}

/** What a backup brings back: a plain list in one card, not interactive. Grows with the stages. */
@Composable
private fun BackupContentsCard() {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            ContentsRow(R.drawable.ic_alarm, stringResource(R.string.label_nav_alarms), stringResource(R.string.desc_backup_item_alarms))
            ContentsRow(R.drawable.ic_public, stringResource(R.string.label_backup_item_cities), stringResource(R.string.desc_backup_item_cities))
            ContentsRow(R.drawable.ic_bookmarks, stringResource(R.string.label_backup_item_presets), stringResource(R.string.desc_backup_item_presets))
            ContentsRow(R.drawable.ic_tune, stringResource(R.string.label_backup_item_settings), stringResource(R.string.desc_backup_item_settings))
        }
    }
}

@Composable
private fun ContentsRow(@DrawableRes icon: Int, title: String, supporting: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The picked file: its name, what it holds and when it was made, and Change to pick another. */
@Composable
private fun BackupFileCard(name: String, valid: BackupCheck.Valid, enabled: Boolean, onChange: () -> Unit) {
    val context = LocalContext.current
    val summary = stringResource(R.string.label_backup_file_summary, valid.alarms, valid.cities, backupDate(context, valid.backup.createdAt))
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_description), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "$name, $summary" }) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onChange, enabled = enabled) { Text(stringResource(R.string.button_backup_change)) }
        }
    }
}

/** A refused file, in place of the file card; announced when it appears. */
@Composable
private fun RestoreErrorCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_error), contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Start inset of the title and texts, which the logo's mark lines up with. */
private val TITLE_INSET = 8.dp

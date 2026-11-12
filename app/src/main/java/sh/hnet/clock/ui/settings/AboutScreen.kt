@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import sh.hnet.clock.BuildConfig
import sh.hnet.clock.R
import sh.hnet.clock.about.WhatsNew
import sh.hnet.clock.ui.components.AppMark
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.SettingsLinkRow
import sh.hnet.clock.ui.components.SettingsNavigationRow
import sh.hnet.clock.ui.components.SettingsNote
import sh.hnet.clock.ui.components.SettingsPage

/** Where the source lives; handed to the browser (Clock itself has no network access). */
const val SOURCE_ADDRESS = "git.hnet.sh/hkr/clock"

/**
 * Settings › About, as ComfyChair's: the app's mark, name and version, then What's new (badge New
 * until opened for this version), License with the open-source licenses under it, Source code; then
 * the privacy note.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenWhatsNew: () -> Unit, onOpenLicense: () -> Unit, onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    // No row without this version's notes; the badge until the page was opened (checked again on coming back)
    val notes = remember { WhatsNew.notes(context) }
    var seen by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        seen = WhatsNew.isSeen(context)
        onPauseOrDispose { }
    }
    val version = BuildConfig.VERSION_NAME.removePrefix("v")
    val noBrowser = stringResource(R.string.msg_no_browser)
    SettingsPage(title = stringResource(R.string.title_settings_about), onBack = onBack) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // The launcher icon as a launcher shows it; the name follows as text
            AppMark(240.dp, Modifier.padding(top = 8.dp, bottom = 24.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.label_version_value, version), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsGroup {
            notes?.let { notes ->
                row { shapes ->
                    val count = notes.changelog.itemCount
                    SettingsNavigationRow(
                        shapes,
                        R.drawable.ic_new_releases,
                        stringResource(R.string.whats_new_title),
                        supporting = if (count > 0) pluralStringResource(R.plurals.whats_new_row_supporting, count, notes.version, count)
                        else stringResource(R.string.label_version_value, notes.version),
                        badge = if (seen) null else stringResource(R.string.whats_new_badge),
                        onClick = onOpenWhatsNew,
                    )
                }
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_workspace_premium, stringResource(R.string.title_about_license), stringResource(R.string.label_about_license_name), onClick = onOpenLicense)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_description, stringResource(R.string.label_licenses), stringResource(R.string.desc_licenses), onClick = onOpenLicenses)
            }
            row { shapes ->
                SettingsLinkRow(shapes, R.drawable.ic_code, stringResource(R.string.title_about_source), SOURCE_ADDRESS) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://$SOURCE_ADDRESS".toUri()))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        SettingsNote(stringResource(R.string.msg_about_privacy))
    }
}

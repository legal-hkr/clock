@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.settings

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.clock.R
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.SettingsPage
import sh.hnet.clock.ui.components.settingsRowColors

/**
 * What ships in the app, all under the Apache License 2.0 (checked against the release runtime
 * classpath; update when a dependency with another license is added).
 */
private val COMPONENTS = listOf(
    "AndroidX (Jetpack Compose, Material 3, Room, Navigation 3, DataStore, Lifecycle, Activity, Core)",
    "Kotlin",
    "kotlinx.coroutines",
    "kotlinx.serialization",
    "Guava",
    "Okio",
    "JSpecify",
    "JetBrains Java Annotations",
    "Material Symbols",
)

/** Settings › About › Open-source licenses, as Music's: each component and its license, the text on tap. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val resources = LocalResources.current
    val licenseText = remember(resources) { resources.openRawResource(R.raw.apache_license_2_0).bufferedReader().use { it.readText() } }
    var showing by rememberSaveable { mutableStateOf(false) }
    val license = stringResource(R.string.label_apache_license)
    SettingsPage(title = stringResource(R.string.label_licenses), onBack = onBack) {
        SettingsGroup {
            COMPONENTS.forEach { name ->
                row { shapes ->
                    SegmentedListItem(
                        onClick = { showing = true },
                        shapes = shapes,
                        colors = settingsRowColors(),
                        supportingContent = { Text(license) },
                    ) {
                        Text(name)
                    }
                }
            }
        }
    }
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(license) },
            text = {
                SelectionContainer(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(licenseText, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showing = false }) { Text(stringResource(R.string.button_close)) } },
        )
    }
}

package sh.hnet.clock.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.clock.R
import sh.hnet.clock.ui.components.SettingsGroup
import sh.hnet.clock.ui.components.SettingsPage
import sh.hnet.clock.ui.components.SettingsSwitchRow
import sh.hnet.clock.ui.theme.ClockTheme

/**
 * The settings of one placed analog or round digital clock widget (lighter widgets handoff of 13 Nov
 * 2026, §6): whether its cookie is see-through. Opened from the widget only – placing it asks
 * nothing (`configuration_optional`) and gives the solid cookie. The switch acts at once, as every
 * setting in the app does: the widget on the home screen changes with it.
 */
class ClockWidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        // Nothing here is left unsaved, so the page always ends well
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val round = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className == RoundClockWidget::class.java.name
        val context = this
        setContent {
            ClockTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    var seeThrough by rememberSaveable { mutableStateOf(WidgetSettings.seeThrough(context, id)) }
                    SettingsPage(title = stringResource(if (round) R.string.widget_digital_round_name else R.string.widget_analog_name), onBack = ::finish) {
                        SettingsGroup {
                            row { shapes ->
                                SettingsSwitchRow(
                                    shapes, R.drawable.ic_opacity, stringResource(R.string.label_see_through),
                                    checked = seeThrough,
                                    onCheckedChange = { on ->
                                        seeThrough = on
                                        WidgetSettings.setSeeThrough(context, id, on)
                                        if (round) Widgets.round(context) else Widgets.analog(context)
                                    },
                                    supporting = stringResource(R.string.desc_see_through),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

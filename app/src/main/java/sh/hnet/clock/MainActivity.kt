package sh.hnet.clock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
import sh.hnet.clock.about.WhatsNew
import sh.hnet.clock.ui.ClockApp
import sh.hnet.clock.ui.Tab
import sh.hnet.clock.ui.theme.ClockTheme
import sh.hnet.clock.ui.welcome.FirstRun
import sh.hnet.clock.ui.welcome.WelcomeWizard

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) WhatsNew.recordStart(this)
        setContent {
            ClockTheme {
                val app = application as ClockApplication
                // The first-run wizard until it was finished once; kept on rotation
                var firstRun by rememberSaveable { mutableStateOf(FirstRun.isNeeded(app)) }
                var restored by rememberSaveable { mutableStateOf(false) }
                // The destination used last, read once before the shell is shown (no flash of another one)
                var startTab by rememberSaveable { mutableStateOf<Tab?>(null) }
                LaunchedEffect(Unit) {
                    if (startTab == null) startTab = app.preferences.lastDestination.first()
                }
                val start = startTab
                when {
                    firstRun -> WelcomeWizard(onDone = { fromBackup ->
                        FirstRun.done(app)
                        restored = fromBackup
                        firstRun = false
                    })
                    start == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                    else -> ClockApp(startTab = start, restored = restored)
                }
            }
        }
    }
}

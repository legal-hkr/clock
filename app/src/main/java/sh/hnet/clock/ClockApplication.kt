package sh.hnet.clock

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import sh.hnet.clock.backup.BackupManager

class ClockApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    val backup: BackupManager by lazy { BackupManager(this, getSharedPreferences("backup", MODE_PRIVATE)) }
}

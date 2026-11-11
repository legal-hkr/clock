package sh.hnet.clock.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import sh.hnet.clock.notify.Notifications

/**
 * Asking for the notification permission in context (SPEC §3.11.2): the returned function asks if
 * notifications aren't allowed yet. With `onlyOnce` it asks the first time ever and never again (the
 * stopwatch, which works without them); otherwise every time, for as long as the system still
 * shows its dialog (timers and alarms, which can't ring properly without). [onAnswer] runs after
 * the user answered.
 */
@Composable
fun rememberNotificationAsk(onAnswer: () -> Unit): (onlyOnce: Boolean) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onAnswer() }
    return { onlyOnce ->
        if (!Notifications.allowed(context) && (!onlyOnce || takeFirstAsk(context))) {
            markRequested(context)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Remembers that the system's dialog was asked for, so [mayAskNotifications] can tell "never asked" from "refused for good". */
fun markRequested(context: Context) = context.getSharedPreferences("first_run", Context.MODE_PRIVATE).edit { putBoolean(REQUESTED, true) }

/**
 * Whether the system would still show its permission dialog: before the first request, and after
 * one refusal. After the second the system answers for the user, and only its settings page helps.
 */
fun mayAskNotifications(context: Context, activity: Activity?): Boolean =
    !context.getSharedPreferences("first_run", Context.MODE_PRIVATE).getBoolean(REQUESTED, false) ||
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true

/** True the first time only. */
private fun takeFirstAsk(context: Context): Boolean {
    val store = context.getSharedPreferences("first_run", Context.MODE_PRIVATE)
    if (store.getBoolean(ASKED, false)) return false
    store.edit { putBoolean(ASKED, true) }
    return true
}

private const val ASKED = "notifications_asked"
private const val REQUESTED = "notifications_requested"

package sh.hnet.clock.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import sh.hnet.clock.R
import sh.hnet.clock.notify.Notifications
import sh.hnet.clock.reliability.Checks
import sh.hnet.clock.reliability.Problem
import sh.hnet.clock.reliability.Reliability
import sh.hnet.clock.reliability.Ringing

/** The phone's settings alarms depend on, as a page sees them, and the way to have them read again. */
class ReliabilityState(val checks: Checks, val refresh: () -> Unit)

/**
 * Reads what alarms depend on (SPEC §3.2.8): when the page appears, on every return to it – the
 * user may have been to the system's settings – and whenever the phone's volume changes.
 */
@Composable
fun rememberReliability(): ReliabilityState {
    val context = LocalContext.current
    var checks by remember { mutableStateOf(Reliability.read(context)) }
    val refresh = remember(context) { { checks = Reliability.read(context) } }
    LifecycleResumeEffect(context) {
        refresh()
        onPauseOrDispose { }
    }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return ReliabilityState(checks, refresh)
}

/**
 * The one action that fixes a problem: the system's permission dialog while it can still be shown,
 * else the page of the system's settings where the answer is; the volume is turned up in place.
 */
@Composable
fun rememberProblemFix(what: Ringing, refresh: () -> Unit): (Problem) -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    return { problem ->
        when (problem) {
            Problem.NOTIFICATIONS -> {
                val checks = Reliability.read(context)
                when {
                    // Allowed as a whole: it is a ringing channel that was blocked
                    checks.notifications -> {
                        val channel = if (what == Ringing.TIMERS || checks.alarmChannel) Notifications.CHANNEL_RINGING_TIMERS else Notifications.CHANNEL_RINGING_ALARMS
                        context.open(
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .putExtra(Settings.EXTRA_CHANNEL_ID, channel),
                        )
                    }
                    mayAskNotifications(context, activity) -> {
                        markRequested(context)
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else -> context.open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
            Problem.FULL_SCREEN -> context.open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, context.packageUri()))
            Problem.DO_NOT_DISTURB -> context.open(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
            // The system has no page of its own for an app's battery use: the app's page leads to it
            Problem.BATTERY -> context.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, context.packageUri()))
            Problem.VOLUME -> {
                Reliability.turnUp(context)
                refresh()
            }
        }
    }
}

private fun Context.packageUri(): Uri = Uri.fromParts("package", packageName, null)

/** Opens a page of the system's settings; on a phone that lacks it, the app's own page there. */
fun Context.open(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No settings app at all: nothing to open
        }
    }
}

@DrawableRes
fun Problem.icon(): Int = when (this) {
    Problem.NOTIFICATIONS -> R.drawable.ic_notifications_off
    Problem.FULL_SCREEN -> R.drawable.ic_fullscreen
    Problem.DO_NOT_DISTURB -> R.drawable.ic_do_not_disturb_on
    Problem.BATTERY -> R.drawable.ic_battery_alert
    Problem.VOLUME -> R.drawable.ic_volume_off
}

@StringRes
private fun Problem.title(): Int = when (this) {
    Problem.NOTIFICATIONS -> R.string.title_problem_notifications
    Problem.FULL_SCREEN -> R.string.title_problem_full_screen
    Problem.DO_NOT_DISTURB -> R.string.title_problem_dnd
    Problem.BATTERY -> R.string.title_problem_battery
    Problem.VOLUME -> R.string.title_problem_volume
}

/** The one sentence under the title: what it means for alarms, or on Timer for timers. */
@StringRes
private fun Problem.message(what: Ringing): Int {
    val timers = what == Ringing.TIMERS
    return when (this) {
        Problem.NOTIFICATIONS -> if (timers) R.string.msg_problem_notifications_timer else R.string.msg_problem_notifications
        Problem.FULL_SCREEN -> if (timers) R.string.msg_problem_full_screen_timer else R.string.msg_problem_full_screen
        Problem.DO_NOT_DISTURB -> R.string.msg_problem_dnd
        Problem.BATTERY -> if (timers) R.string.msg_problem_battery_timer else R.string.msg_problem_battery
        Problem.VOLUME -> R.string.msg_problem_volume
    }
}

@StringRes
private fun Problem.button(): Int = when (this) {
    Problem.NOTIFICATIONS -> R.string.button_problem_notifications
    Problem.FULL_SCREEN -> R.string.button_problem_full_screen
    Problem.DO_NOT_DISTURB -> R.string.button_problem_dnd
    Problem.BATTERY -> R.string.button_problem_battery
    Problem.VOLUME -> R.string.button_problem_volume
}

/**
 * The problem card (SPEC §3.2.8): the app's one real error – an alarm that will not ring – said
 * where alarms are set, with the single action that fixes it. One card at a time; fixing one
 * reveals the next. Announced once when it appears.
 */
@Composable
fun ProblemCard(problem: Problem, what: Ringing, onFix: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(problem.icon()), contentDescription = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(problem.title()), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(problem.message(what)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(
                onClick = onFix,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(problem.button()))
            }
        }
    }
}

package com.syrok0010.nextgallery.feature.upload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.syrok0010.nextgallery.R
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.combine
import org.koin.compose.koinInject

/**
 * Starts the upload service while [queue] has jobs nobody runs and the app is visible. Before the
 * first upload it asks for the notification permission once; uploads start either way.
 */
@Composable
internal fun QueuedUploads(
    queue: UploadQueue = koinInject(),
    startService: (Context) -> Unit = { UploadForegroundService.start(it) },
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("upload_permissions", Context.MODE_PRIVATE)
    }
    val start by rememberUpdatedState(startService)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Survives recreation while the dialog is shown, so the restored screen does not ask again.
    var asking by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { asking = false }

    LaunchedEffect(queue, lifecycle, context) {
        // A foreground service may only start from a visible app.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Each newly queued job starts the service once more: a worker that is just finishing
            // takes it instead of leaving it for the next visit.
            var started = emptySet<String>()
            combine(queue.waiting, snapshotFlow { asking }, ::Pair).collect { (waiting, isAsking) ->
                if (started.containsAll(waiting) || isAsking) return@collect
                val shouldRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) !=
                    PackageManager.PERMISSION_GRANTED &&
                    !preferences.getBoolean("notifications_requested", false)
                if (shouldRequest) {
                    preferences.edit().putBoolean("notifications_requested", true).apply()
                    asking = true
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (startUploads(context, start)) {
                    started = started + waiting
                } else {
                    // Retrying while still visible would most likely be refused again; the
                    // jobs wait for the next time the app comes to the foreground.
                    awaitCancellation()
                }
            }
        }
    }
}

/** False if the system refused to start the service; the jobs stay queued. */
private fun startUploads(context: Context, start: (Context) -> Unit): Boolean {
    try {
        start(context)
    } catch (_: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException and the background start limit before Android 12.
        Toast.makeText(context, R.string.upload_start_refused, Toast.LENGTH_LONG).show()
        return false
    }
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        Toast.makeText(context, R.string.upload_without_notifications, Toast.LENGTH_LONG).show()
    }
    return true
}

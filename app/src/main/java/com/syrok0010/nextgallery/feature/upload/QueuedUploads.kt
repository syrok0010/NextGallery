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
 * Starts the upload service for batches waiting in [queue] while the app is visible. Before the
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
            combine(queue.waiting, snapshotFlow { asking }, ::Pair).collect { (waiting, isAsking) ->
                if (waiting.isEmpty() || isAsking) return@collect
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
                } else if (!handOff(context, queue, start)) {
                    // Retrying while still visible would most likely be refused again; the
                    // batches wait for the next time the app comes to the foreground.
                    awaitCancellation()
                }
            }
        }
    }
}

/** Starts the service once for all waiting batches; false if the system refused to start it. */
private fun handOff(context: Context, queue: UploadQueue, start: (Context) -> Unit): Boolean {
    val released = queue.release()
    if (released.isEmpty()) return true
    try {
        start(context)
    } catch (_: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException and the background start limit before Android 12.
        queue.restore(released)
        Toast.makeText(context, R.string.upload_start_refused, Toast.LENGTH_LONG).show()
        return false
    }
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        Toast.makeText(context, R.string.upload_without_notifications, Toast.LENGTH_LONG).show()
    }
    return true
}

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
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.R
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
    val waiting by queue.waiting.collectAsStateWithLifecycle()
    // Survives recreation while the dialog is shown, so the restored screen does not ask again.
    var asking by rememberSaveable { mutableStateOf(false) }
    val release = {
        asking = false
        val starts = queue.release()
        if (starts > 0 && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Toast.makeText(context, R.string.upload_without_notifications, Toast.LENGTH_LONG).show()
        }
        repeat(starts) { start(context) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { release() }

    LaunchedEffect(waiting, asking) {
        if (waiting.isEmpty() || asking) return@LaunchedEffect
        val shouldRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notifications_requested", false)
        if (shouldRequest) {
            preferences.edit().putBoolean("notifications_requested", true).apply()
            asking = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            release()
        }
    }
}

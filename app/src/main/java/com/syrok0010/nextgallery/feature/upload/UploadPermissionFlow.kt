package com.syrok0010.nextgallery.feature.upload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.syrok0010.nextgallery.R
import kotlinx.serialization.json.Json

/** Keeps the upload snapshot across the permission dialog, including activity recreation. */
@Composable
internal fun rememberUploadAction(
    onUpload: (List<UploadSelection>) -> Unit,
): (List<UploadSelection>, onStarted: () -> Unit) -> Unit {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("upload_permissions", Context.MODE_PRIVATE)
    }
    val startUpload by rememberUpdatedState(onUpload)
    var pendingPayload by rememberSaveable { mutableStateOf<String?>(null) }
    var onStarted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val completeUpload = {
        val payload = pendingPayload
        pendingPayload = null
        val started = onStarted
        onStarted = null
        if (payload != null) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Toast
                    .makeText(
                        context,
                        R.string.upload_without_notifications,
                        Toast.LENGTH_LONG,
                    ).show()
            }
            startUpload(Json.decodeFromString<List<UploadSelection>>(payload))
            started?.invoke()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { completeUpload() }

    return { selections, started ->
        if (selections.isEmpty()) {
            started()
        } else if (pendingPayload == null) {
            pendingPayload = Json.encodeToString(selections)
            onStarted = started
            val shouldRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) !=
                PackageManager.PERMISSION_GRANTED &&
                !preferences.getBoolean("notifications_requested", false)
            if (shouldRequest) {
                preferences.edit().putBoolean("notifications_requested", true).apply()
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                completeUpload()
            }
        }
    }
}

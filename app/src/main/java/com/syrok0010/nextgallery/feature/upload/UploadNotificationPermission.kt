package com.syrok0010.nextgallery.feature.upload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

/**
 * Asks for the notification permission once, when the first upload is queued. Uploads run either
 * way; without the permission the progress and the result are only in the app.
 */
@Composable
internal fun UploadNotificationPermission(queue: UploadQueue = koinInject()) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(queue, context) {
        val preferences = context.getSharedPreferences("upload_permissions", Context.MODE_PRIVATE)
        if (preferences.getBoolean("notifications_requested", false)) return@LaunchedEffect
        queue.waiting.first { it.isNotEmpty() }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return@LaunchedEffect
        preferences.edit().putBoolean("notifications_requested", true).apply()
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

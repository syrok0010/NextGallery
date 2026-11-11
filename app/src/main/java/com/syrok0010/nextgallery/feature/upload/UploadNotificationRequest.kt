package com.syrok0010.nextgallery.feature.upload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asks once for the notification permission; call it when the user has just queued an upload.
 * Uploads run either way; without the permission their progress and result are only in the app.
 */
@Composable
internal fun rememberUploadNotificationRequest(): suspend () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(context, launcher) {
        request@{
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@request
            val permission = Manifest.permission.POST_NOTIFICATIONS
            val granted = ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
            if (granted || alreadyAsked(context)) return@request
            launcher.launch(permission)
        }
    }
}

/** Marks the question as asked and tells whether it was asked before. */
private suspend fun alreadyAsked(context: Context): Boolean =
    withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences("upload_permissions", Context.MODE_PRIVATE)
        preferences.getBoolean(ASKED, false).also { asked ->
            if (!asked) preferences.edit().putBoolean(ASKED, true).apply()
        }
    }

private const val ASKED = "notifications_requested"

package com.syrok0010.nextgallery.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class LocalMediaPermissionMode {
    Full,
    Partial,
    Denied,
}

data class LocalMediaPermissionGrants(
    val legacyStorage: Boolean = false,
    val images: Boolean = false,
    val videos: Boolean = false,
    val selectedVisualMedia: Boolean = false,
)

object LocalMediaPermissionPolicy {
    fun mode(sdkInt: Int, grants: LocalMediaPermissionGrants): LocalMediaPermissionMode = when {
        sdkInt <= Build.VERSION_CODES.S_V2 && grants.legacyStorage -> LocalMediaPermissionMode.Full
        sdkInt >= Build.VERSION_CODES.TIRAMISU && grants.images && grants.videos -> LocalMediaPermissionMode.Full
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && grants.selectedVisualMedia -> LocalMediaPermissionMode.Partial
        else -> LocalMediaPermissionMode.Denied
    }

    fun requestedPermissions(sdkInt: Int): Array<String> = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun shouldExplainAutomatically(
        mode: LocalMediaPermissionMode,
        explanationAlreadyShown: Boolean,
    ): Boolean = mode != LocalMediaPermissionMode.Full && !explanationAlreadyShown
}

class LocalMediaPermissionCoordinator(
    private val context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun currentMode(): LocalMediaPermissionMode {
        fun granted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        return LocalMediaPermissionPolicy.mode(
            sdkInt = Build.VERSION.SDK_INT,
            grants = LocalMediaPermissionGrants(
                legacyStorage = granted(Manifest.permission.READ_EXTERNAL_STORAGE),
                images = granted(Manifest.permission.READ_MEDIA_IMAGES),
                videos = granted(Manifest.permission.READ_MEDIA_VIDEO),
                selectedVisualMedia = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            ),
        )
    }

    fun requestedPermissions(): Array<String> =
        LocalMediaPermissionPolicy.requestedPermissions(Build.VERSION.SDK_INT)

    fun shouldExplainAutomatically(): Boolean = LocalMediaPermissionPolicy.shouldExplainAutomatically(
        mode = currentMode(),
        explanationAlreadyShown = preferences.getBoolean(KEY_AUTO_EXPLAINED, false),
    )

    fun markAutomaticExplanationShown() {
        preferences.edit().putBoolean(KEY_AUTO_EXPLAINED, true).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "local-media-permission"
        const val KEY_AUTO_EXPLAINED = "auto-explained"
    }
}

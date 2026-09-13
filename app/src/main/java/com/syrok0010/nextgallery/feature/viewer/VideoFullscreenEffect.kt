package com.syrok0010.nextgallery.feature.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal fun VideoFullscreenEffect(isFullscreen: Boolean) {
    val activity = LocalContext.current.videoActivity()
    DisposableEffect(isFullscreen, activity) {
        val previousOrientation = activity?.requestedOrientation
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val insets = window?.decorView?.let { ViewCompat.getRootWindowInsets(it) }
        val previousBehavior = controller?.systemBarsBehavior
        if (isFullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (isFullscreen) {
                previousOrientation?.let { activity?.requestedOrientation = it }
                previousBehavior?.let { controller?.systemBarsBehavior = it }
                if (insets?.isVisible(WindowInsetsCompat.Type.statusBars()) != false) {
                    controller?.show(WindowInsetsCompat.Type.statusBars())
                }
                if (insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) != false) {
                    controller?.show(WindowInsetsCompat.Type.navigationBars())
                }
            }
        }
    }
}

private fun Context.videoActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}

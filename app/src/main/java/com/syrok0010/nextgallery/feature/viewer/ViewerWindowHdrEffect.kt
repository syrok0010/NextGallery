package com.syrok0010.nextgallery.feature.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun ViewerWindowHdrEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    SideEffect(activity, enabled) {
        activity?.window?.colorMode = if (enabled) {
            ActivityInfo.COLOR_MODE_HDR
        } else {
            ActivityInfo.COLOR_MODE_DEFAULT
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        }
    }
}

private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) {
            return currentContext
        }
        currentContext = currentContext.baseContext
    }
    return null
}

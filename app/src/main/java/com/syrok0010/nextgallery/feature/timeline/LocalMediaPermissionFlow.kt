package com.syrok0010.nextgallery.ui.timeline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.syrok0010.nextgallery.data.local.LocalMediaPermissionCoordinator
import com.syrok0010.nextgallery.data.local.LocalMediaPermissionMode

@Composable
internal fun LocalMediaPermissionFlow(
    isSignedIn: Boolean,
    permissionMode: LocalMediaPermissionMode?,
    permissionCoordinator: LocalMediaPermissionCoordinator,
    onPermissionModeChanged: (LocalMediaPermissionMode) -> Unit,
) {
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    var permissionRequestInFlight by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        onPermissionModeChanged(permissionCoordinator.currentMode())
    }

    DisposableEffect(lifecycleOwner, isSignedIn) {
        fun synchronizePermission() {
            if (isSignedIn) {
                onPermissionModeChanged(permissionCoordinator.currentMode())
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (permissionRequestInFlight) {
                    permissionRequestInFlight = false
                } else {
                    synchronizePermission()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            synchronizePermission()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isSignedIn, permissionMode) {
        if (!isSignedIn) {
            showExplanation = false
        } else if (
            permissionMode != null &&
            permissionCoordinator.shouldExplainAutomatically()
        ) {
            permissionCoordinator.markAutomaticExplanationShown()
            showExplanation = true
        }
    }

    if (showExplanation) {
        LocalMediaPermissionExplanationDialog(
            onDismiss = { showExplanation = false },
            onRequestPermission = {
                showExplanation = false
                permissionRequestInFlight = true
                permissionLauncher.launch(permissionCoordinator.requestedPermissions())
            },
        )
    }
}

package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.syrok0010.nextgallery.R

@Composable
internal fun LocalMediaPermissionExplanationDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_media_permission_title)) },
        text = { Text(stringResource(R.string.local_media_permission_explanation)) },
        confirmButton = {
            TextButton(onClick = onRequestPermission) {
                Text(stringResource(R.string.action_allow_local_media))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_not_now))
            }
        },
    )
}

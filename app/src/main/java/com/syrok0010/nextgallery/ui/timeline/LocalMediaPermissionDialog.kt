package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.ui.LocalMediaPrompt

@Composable
internal fun LocalMediaPermissionDialog(
    prompt: LocalMediaPrompt,
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (prompt == LocalMediaPrompt.None) return

    val isSettings = prompt == LocalMediaPrompt.OpenSettings
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(
                if (prompt == LocalMediaPrompt.PartialRequiresFull) {
                    R.string.local_media_partial_title
                } else {
                    R.string.local_media_permission_title
                },
            ))
        },
        text = {
            Text(stringResource(
                when (prompt) {
                    LocalMediaPrompt.Explanation -> R.string.local_media_permission_explanation
                    LocalMediaPrompt.PartialRequiresFull -> R.string.local_media_partial_explanation
                    LocalMediaPrompt.OpenSettings -> R.string.local_media_settings_explanation
                    LocalMediaPrompt.None -> error("Prompt is not visible")
                },
            ))
        },
        confirmButton = {
            TextButton(onClick = if (isSettings) onOpenSettings else onRequestPermission) {
                Text(stringResource(if (isSettings) R.string.action_open_settings else R.string.action_allow_local_media))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_not_now))
            }
        },
    )
}

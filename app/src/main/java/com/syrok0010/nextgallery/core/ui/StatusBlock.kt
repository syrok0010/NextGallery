package com.syrok0010.nextgallery.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.ui.AppMessageUiState
import com.syrok0010.nextgallery.ui.asString

@Composable
internal fun StatusBlock(messageState: AppMessageUiState) {
    val message = messageState.error ?: messageState.status ?: return
    val color = if (messageState.error != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = message.asString(),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = color,
        style = MaterialTheme.typography.bodyMedium,
    )
}

package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.syrok0010.nextgallery.core.media.MediaItem

/** The same action consumes a single current object without changing the grid selection. */
@Composable
internal fun ViewerSelectionActions(
    actions: List<SelectionAction>,
    item: MediaItem,
    enabled: Boolean = true,
) {
    Row {
        actions.forEach { action ->
            IconButton(
                enabled = enabled,
                onClick = { action.perform(listOf(item)) {} },
            ) {
                Icon(action.icon, contentDescription = action.label, tint = Color.White)
            }
        }
    }
}

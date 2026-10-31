package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.syrok0010.nextgallery.core.media.MediaItem

/** The same action consumes a single current object without changing the grid selection. */
@Composable
internal fun ViewerSelectionActions(actions: List<SelectionAction>, item: MediaItem) {
    var locked by remember(item.mediaId) { mutableStateOf(false) }
    Row {
        actions.forEach { action ->
            IconButton(
                enabled = !locked,
                onClick = {
                    locked = action.blocksSelectionUntilDone
                    action.perform(listOf(item)) { locked = false }
                },
            ) {
                Icon(action.icon, contentDescription = action.label, tint = Color.White)
            }
        }
    }
}

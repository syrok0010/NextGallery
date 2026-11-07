package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.viewer.ViewerActionBarHeight

/**
 * Labeled actions under the filmstrip. The same action consumes a single current object without
 * changing the grid selection; an action with nothing left to do shows its done state disabled.
 */
@Composable
internal fun ViewerSelectionActions(actions: List<SelectionAction>, item: MediaItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ViewerActionBarHeight)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            val done = action.done?.takeIf { it.appliesTo(item) }
            ViewerActionButton(
                icon = done?.icon ?: action.icon,
                label = done?.label ?: action.label,
                enabled = done == null,
                // The viewer chrome stays dark in either theme, so the colors are fixed.
                tint = if (action.isDestructive) ViewerDestructive else Color.White,
                onClick = { action.perform(listOf(item)) {} },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ViewerActionButton(
    icon: Painter,
    label: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (enabled) tint else tint.copy(alpha = 0.4f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        contentColor = color,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = color)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val ViewerDestructive = Color(0xFFF2B8B5)

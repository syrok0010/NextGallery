package com.syrok0010.nextgallery.feature.collection

import androidx.compose.ui.graphics.painter.Painter
import com.syrok0010.nextgallery.core.media.MediaItem

/**
 * A caller-owned action over a selection snapshot. Calling [perform]'s onDone clears selection;
 * dismissing a confirmation or rejecting a request must leave it untouched.
 * [blocksSelectionUntilDone] prevents other actions and cancellation while handing off immediate work.
 */
internal class SelectionAction(
    val icon: Painter,
    val label: String,
    val isPrimary: Boolean = false,
    val inOverflow: Boolean = false,
    val isDestructive: Boolean = false,
    val blocksSelectionUntilDone: Boolean = false,
    val perform: (items: List<MediaItem>, onDone: () -> Unit) -> Unit,
)

internal data class SelectionActionPlacement(
    val buttons: List<SelectionAction>,
    val overflow: List<SelectionAction>,
)

/** Keep the first primary action visible; reserve a slot for overflow when needed. */
internal fun placeSelectionActions(actions: List<SelectionAction>): SelectionActionPlacement {
    val primary = actions.firstOrNull { it.isPrimary }
    val secondary = actions.filter { it !== primary && !it.inOverflow }
    val buttonCount = if (actions.size > 3 || actions.any { it.inOverflow }) 2 else 3
    val visible = secondary.take(buttonCount - if (primary == null) 0 else 1).toSet()
    return SelectionActionPlacement(
        buttons = actions.filter { it === primary || it in visible },
        overflow = actions.filter { it !== primary && it !in visible },
    )
}

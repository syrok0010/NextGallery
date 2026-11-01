package com.syrok0010.nextgallery.feature.collection

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionActionPlacementTest {
    @Test fun currentActionsKeepTheirOrderAndStayVisible() {
        val actions = listOf(action("delete"), action("album"), action("upload", primary = true))
        val placement = placeSelectionActions(actions)
        assertEquals(actions, placement.buttons)
        assertEquals(emptyList<SelectionAction>(), placement.overflow)
    }

    @Test fun overflowReservesSpaceAndKeepsPrimaryEvenAtTheEnd() {
        val actions =
            listOf(action("delete"), action("album"), action("extra"), action("upload", true))
        val placement = placeSelectionActions(actions)
        assertEquals(listOf(actions[0], actions[3]), placement.buttons)
        assertEquals(listOf(actions[1], actions[2]), placement.overflow)
    }

    @Test fun actionsWithoutPrimaryStillHaveAnOverflowSlot() {
        val actions = List(5) { action("action $it") }
        val placement = placeSelectionActions(actions)
        assertEquals(actions.take(2), placement.buttons)
        assertEquals(actions.drop(2), placement.overflow)
        assertEquals(
            SelectionActionPlacement(emptyList(), emptyList()),
            placeSelectionActions(emptyList()),
        )
    }

    @Test fun albumSecondaryActionsStayInMenuWhilePrimaryHasRoomForItsLabel() {
        val primary = action("Убрать из альбома", primary = true)
        val secondary = listOf("Добавить в другой альбом", "Удалить").map {
            SelectionAction(ColorPainter(Color.Black), it, inOverflow = true) { _, _ -> }
        }
        assertEquals(
            SelectionActionPlacement(listOf(primary), secondary),
            placeSelectionActions(listOf(primary) + secondary),
        )
    }

    private fun action(label: String, primary: Boolean = false) =
        SelectionAction(
            icon = ColorPainter(Color.Black),
            label = label,
            isPrimary = primary,
            perform = { _, _ -> },
        )
}

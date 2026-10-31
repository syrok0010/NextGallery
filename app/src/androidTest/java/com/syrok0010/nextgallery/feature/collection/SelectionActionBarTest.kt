package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionActionBarTest {
    @get:Rule val rule = createComposeRule()

    @Test fun currentActionsKeepOrderAndUploadLabelFitsPhoneWidth() {
        val actions = listOf(action("Удалить"), action("В альбом"), action("Загрузить", true))
        var performed: SelectionAction? = null
        show(actions, onPerform = { performed = it })
        val delete = rule.onNodeWithContentDescription("Удалить")
        val album = rule.onNodeWithContentDescription("В альбом")
        val upload = rule.onNodeWithText("Загрузить")
        assertTrue(delete.getUnclippedBoundsInRoot().left < album.getUnclippedBoundsInRoot().left)
        assertTrue(album.getUnclippedBoundsInRoot().left < upload.getUnclippedBoundsInRoot().left)
        val layouts = mutableListOf<TextLayoutResult>()
        upload.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        val layout = layouts.single()
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(0))
        assertTrue(layout.getLineLeft(0) >= 0)
        assertTrue(layout.getLineRight(0) <= layout.size.width)
        rule.onNodeWithContentDescription("Ещё действия").assertDoesNotExist()
        album.performClick()
        rule.runOnIdle { assertSame(actions[1], performed) }
    }

    @Test fun overflowInvokesTheChosenActionAndDismissesTheMenu() {
        val actions =
            listOf(
                action("Удалить"),
                action("В альбом"),
                action("Другое"),
                action("Загрузить", true),
            )
        var performed: SelectionAction? = null
        show(actions, onPerform = { performed = it })
        rule.onNodeWithText("В альбом").assertDoesNotExist()
        rule.onNodeWithText("Загрузить").assertExists()
        rule.onNodeWithContentDescription("Ещё действия").performClick()
        rule.onNodeWithText("В альбом").performClick()
        rule.runOnIdle { assertSame(actions[1], performed) }
        rule.onNodeWithText("В альбом").assertDoesNotExist()
    }

    @Test fun emptySelectionDisablesActionsButAllowsCancellation() {
        show(listOf(action("В альбом"), action("Загрузить", true)), count = 0)
        rule.onNodeWithContentDescription("В альбом").assertIsNotEnabled()
        rule.onNodeWithText("Загрузить").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Отменить выбор").assertIsEnabled()
    }

    @Test fun handoffDisablesActionsAndCancellation() {
        show(listOf(action("В альбом"), action("Загрузить", true)), locked = true)
        rule.onNodeWithContentDescription("В альбом").assertIsNotEnabled()
        rule.onNodeWithText("Загрузить").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Отменить выбор").assertIsNotEnabled()
    }

    private fun show(
        actions: List<SelectionAction>,
        count: Int = 2,
        locked: Boolean = false,
        onPerform: (SelectionAction) -> Unit = {},
    ) {
        rule.setContent {
            NextGalleryTheme {
                Box(Modifier.fillMaxWidth()) {
                    SelectionActionBar(count, locked, {}, actions, onPerform)
                }
            }
        }
    }

    private fun action(label: String, primary: Boolean = false) =
        SelectionAction(
            icon = ColorPainter(Color.Black),
            label = label,
            isPrimary = primary,
            isDestructive = label == "Удалить",
            perform = { _, _ -> },
        )
}

package com.syrok0010.nextgallery.feature.deletion

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteSelectionDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun mergedObjectsAskWhereToDeleteAndDefaultToEverywhere() {
        var confirmed: DeletionScope? = null
        val plan = DeletionPlan(phoneOnly = 1, cloudOnly = 1, merged = 1)
        rule.setContent {
            NextGalleryTheme {
                DeleteSelectionDialog(
                    plan,
                    cloudTrash = CloudTrashStatus.Available,
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                )
            }
        }
        rule.onNodeWithText("На телефоне и в облаке: 1. Откуда удалить их?").assertExists()
        rule.onNodeWithText("Везде").assertIsSelected()
        rule.onNodeWithText(phoneNote).assertExists()
        rule.onNodeWithText(cloudTrashNote).assertExists()
        rule.onNodeWithText("Только из облака").performClick()
        rule.onNodeWithText("Удалить").performClick()
        rule.runOnIdle { assertEquals(DeletionScope.CloudOnly, confirmed) }
    }

    @Test fun singleCopyObjectsSkipTheScopeChoice() {
        show(DeletionPlan(phoneOnly = 0, cloudOnly = 2, merged = 0), CloudTrashStatus.Available)
        rule.onNodeWithText("Только в облаке: 2").assertExists()
        rule.onNodeWithText("Везде").assertDoesNotExist()
        rule.onNodeWithText(phoneNote).assertDoesNotExist()
    }

    @Test fun serverWithoutTrashWarnsOfPermanentDeletion() {
        show(DeletionPlan(phoneOnly = 0, cloudOnly = 1, merged = 1), CloudTrashStatus.Missing)
        rule.onNodeWithText(permanentNote).assertExists()
        rule.onNodeWithText(cloudTrashNote).assertDoesNotExist()
        rule.onNodeWithText("Удалить").assertIsEnabled()
    }

    @Test fun pendingCheckKeepsPhoneDeletionAvailable() {
        show(DeletionPlan(phoneOnly = 0, cloudOnly = 0, merged = 1), CloudTrashStatus.Checking)
        rule.onNodeWithText(checkingNote).assertExists()
        rule.onNodeWithText("Удалить").assertIsEnabled()
        // Only cloud copies are chosen, and they cannot be deleted before the answer.
        rule.onNodeWithText("Только из облака").performClick()
        rule.onNodeWithText("Удалить").assertIsNotEnabled()
    }

    @Test fun unreachableServerBlocksCloudOnlySelection() {
        show(DeletionPlan(phoneOnly = 0, cloudOnly = 2, merged = 0), CloudTrashStatus.Unreachable)
        rule.onNodeWithText(unreachableNote).assertExists()
        rule.onNodeWithText("Удалить").assertIsNotEnabled()
    }

    @Test fun phoneOnlyScopeHidesCloudNoteButCloudOnlyObjectsKeepIt() {
        show(DeletionPlan(phoneOnly = 0, cloudOnly = 0, merged = 1), CloudTrashStatus.Missing)
        rule.onNodeWithText(permanentNote).assertExists()
        rule.onNodeWithText("Только с телефона").performClick()
        rule.onNodeWithText(permanentNote).assertDoesNotExist()
        rule.onNodeWithText(phoneNote).assertExists()
        rule.onNodeWithText("Только из облака").performClick()
        rule.onNodeWithText(permanentNote).assertExists()
        rule.onNodeWithText(phoneNote).assertDoesNotExist()
    }

    @Test fun cloudOnlyObjectsKeepCloudNoteWhenMergedScopeIsPhoneOnly() {
        show(DeletionPlan(phoneOnly = 0, cloudOnly = 1, merged = 1), CloudTrashStatus.Missing)
        rule.onNodeWithText("Только с телефона").performClick()
        rule.onNodeWithText(permanentNote).assertExists()
    }

    private fun show(plan: DeletionPlan, status: CloudTrashStatus) {
        rule.setContent {
            NextGalleryTheme {
                DeleteSelectionDialog(plan, cloudTrash = status, onDismiss = {}, onConfirm = {})
            }
        }
    }

    private val context get() = androidx.test.platform.app.InstrumentationRegistry
        .getInstrumentation()
        .targetContext
    private val phoneNote get() = context.getString(R.string.delete_phone_trash_note)
    private val cloudTrashNote get() = context.getString(R.string.delete_cloud_trash_note)
    private val permanentNote get() = context.getString(R.string.delete_cloud_permanent_note)
    private val checkingNote get() = context.getString(R.string.delete_cloud_checking_note)
    private val unreachableNote get() = context.getString(R.string.delete_cloud_unreachable_note)
}

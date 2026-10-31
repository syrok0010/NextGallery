package com.syrok0010.nextgallery.feature.deletion

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
                    cloudTrashAvailable = true,
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                )
            }
        }
        rule.onNodeWithText("На телефоне и в облаке: 1. Откуда удалить их?").assertExists()
        rule.onNodeWithText("Везде").assertIsSelected()
        rule.onNodeWithText(trashNote).assertExists()
        rule.onNodeWithText("Только из облака").performClick()
        rule.onNodeWithText("Удалить").performClick()
        rule.runOnIdle { assertEquals(DeletionScope.CloudOnly, confirmed) }
    }

    @Test fun singleCopyObjectsSkipTheScopeChoice() {
        rule.setContent {
            NextGalleryTheme {
                DeleteSelectionDialog(
                    DeletionPlan(phoneOnly = 0, cloudOnly = 2, merged = 0),
                    cloudTrashAvailable = true,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        rule.onNodeWithText("Только в облаке: 2").assertExists()
        rule.onNodeWithText("Везде").assertDoesNotExist()
    }

    @Test fun disabledCloudTrashWarnsOfPermanentDeletion() {
        showCloudDialog(false)
        rule.onNodeWithText(permanentNote).assertExists()
        rule.onNodeWithText(trashNote).assertDoesNotExist()
    }

    @Test fun unknownCloudTrashWarnsOfPermanentDeletion() {
        showCloudDialog(null)
        rule.onNodeWithText(permanentNote).assertExists()
        rule.onNodeWithText(trashNote).assertDoesNotExist()
    }

    @Test fun phoneOnlyScopeDoesNotWarnAboutCloudButCloudOnlyObjectsStillDo() {
        rule.setContent {
            NextGalleryTheme {
                DeleteSelectionDialog(
                    DeletionPlan(phoneOnly = 0, cloudOnly = 0, merged = 1),
                    cloudTrashAvailable = null,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        rule.onNodeWithText(permanentNote).assertExists()
        rule.onNodeWithText("Только с телефона").performClick()
        rule.onNodeWithText(permanentNote).assertDoesNotExist()
        rule.onNodeWithText(phoneNote).assertExists()
        rule.onNodeWithText("Только из облака").performClick()
        rule.onNodeWithText(permanentNote).assertExists()
    }

    @Test fun cloudOnlyObjectsStillWarnWhenMergedScopeIsPhoneOnly() {
        showCloudDialog(false)
        rule.onNodeWithText("Только с телефона").performClick()
        rule.onNodeWithText(permanentNote).assertExists()
    }

    private fun showCloudDialog(available: Boolean?) {
        rule.setContent {
            NextGalleryTheme {
                DeleteSelectionDialog(
                    DeletionPlan(phoneOnly = 0, cloudOnly = 1, merged = 1),
                    cloudTrashAvailable = available,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
    }

    private val context get() = androidx.test.platform.app.InstrumentationRegistry
        .getInstrumentation()
        .targetContext
    private val trashNote get() = context.getString(R.string.delete_trash_note)
    private val permanentNote get() = context.getString(R.string.delete_permanent_cloud_note)
    private val phoneNote get() = context.getString(R.string.delete_phone_trash_note)
}

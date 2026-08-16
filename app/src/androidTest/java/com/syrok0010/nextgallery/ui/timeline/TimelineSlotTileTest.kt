package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.TimelineSlot
import com.syrok0010.nextgallery.data.memories.TimelineSlotKey
import com.syrok0010.nextgallery.domain.media.MediaId
import com.syrok0010.nextgallery.ui.AppMessageUiState
import com.syrok0010.nextgallery.ui.TimelineUiState
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineSlotTileTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun remotePhotoShowsCloudIndicatorAndExposesCloudCopyOnTile() {
        showSlot(mediaItem = mediaItem(isVideo = false))

        composeRule.onNodeWithTag(CLOUD_INDICATOR_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                CLOUD_COPY_DESCRIPTION,
            ) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
        ).assertIsDisplayed()
    }

    @Test
    fun remotePlaceholderShowsDecorativeCloudIndicatorAndExposesCloudCopyOnTile() {
        showSlot(mediaItem = null)

        val indicator = composeRule
            .onNodeWithTag(CLOUD_INDICATOR_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()

        assertFalse(indicator.config.contains(SemanticsActions.OnClick))
        assertFalse(indicator.config.contains(SemanticsProperties.ContentDescription))
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                CLOUD_COPY_DESCRIPTION,
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun remoteVideoCloudIndicatorDoesNotOverlapVideoBadge() {
        showSlot(mediaItem = mediaItem(isVideo = true))

        val cloudBounds = composeRule
            .onNodeWithTag(CLOUD_INDICATOR_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val videoBounds = composeRule
            .onNodeWithText(VIDEO_BADGE, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(
            "Cloud indicator $cloudBounds overlaps video badge $videoBounds",
            cloudBounds.bottom < videoBounds.top,
        )
    }

    @Test
    fun localOnlyPhotoHasNoCloudIndicatorAndRemainsOpenable() {
        val localItem = mediaItem(isVideo = false).copy(
            mediaId = MediaId("local-42"),
            displayName = "local.jpg",
            assetRef = MediaAssetRef.LocalContent("content://media/external/images/media/42"),
        )

        showSlot(mediaItem = localItem)

        composeRule.onNodeWithTag(CLOUD_INDICATOR_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription("local.jpg")
            .assertIsDisplayed()
    }

    @Test
    fun timelineDoesNotOfferDeviceMediaAction() {
        composeRule.setContent {
            MaterialTheme {
                TimelinePanel(
                    state = TimelineUiState(),
                    message = AppMessageUiState(),
                    credentials = CREDENTIALS,
                    onViewportObservation = {},
                    revealMediaId = null,
                    onMediaRevealed = {},
                    registerTimelineTile = { _, _ -> noOpUnregister },
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithText("Фото с устройства").assertDoesNotExist()
    }

    private fun showSlot(mediaItem: MediaItem?) {
        composeRule.setContent {
            MaterialTheme {
                TimelineSlotTile(
                    slot = TimelineSlot(
                        key = TimelineSlotKey(dayId = DAY_ID, indexInDay = 0),
                        dayId = DAY_ID,
                        indexInDay = 0,
                        mediaItem = mediaItem,
                    ),
                    credentials = CREDENTIALS,
                    registerTimelineTile = { _, _ -> noOpUnregister },
                    onSelect = {},
                )
            }
        }
    }

    private fun mediaItem(isVideo: Boolean): MediaItem = MediaItem(
        mediaId = MediaId("remote-42"),
        remoteFileId = 42,
        dayId = DAY_ID,
        day = LocalDate.ofEpochDay(DAY_ID.toLong()),
        displayName = "remote.jpg",
        mimeType = if (isVideo) "video/mp4" else "image/jpeg",
        width = 1200,
        height = 800,
        etag = "etag",
        livePhotoId = null,
        auid = null,
        buid = null,
        sharedBy = null,
        takenAtEpochSeconds = null,
        isVideo = isVideo,
        videoDurationSeconds = if (isVideo) 12 else null,
        isFavorite = false,
        isHidden = false,
        assetRef = MediaAssetRef.MemoriesFile(photoFileId = 42),
    )

    private companion object {
        const val DAY_ID = 20_660
        const val CLOUD_INDICATOR_TAG = "remote-cloud-indicator"
        const val CLOUD_COPY_DESCRIPTION = "Облачная копия"
        const val VIDEO_BADGE = "VIDEO"
        val CREDENTIALS = AccountCredentials(
            serverUrl = "https://cloud.example.com",
            loginName = "test",
            appPassword = "secret",
        )
        val noOpUnregister: () -> Unit = {}
    }
}

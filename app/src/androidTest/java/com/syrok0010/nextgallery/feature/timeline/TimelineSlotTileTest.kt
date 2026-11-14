package com.syrok0010.nextgallery.feature.timeline

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.collection.MediaSlot
import com.syrok0010.nextgallery.feature.collection.MediaSlotKey
import com.syrok0010.nextgallery.feature.collection.MediaSlotTile
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import com.syrok0010.nextgallery.string
import org.junit.Assert.assertEquals
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

        composeRule
            .onNodeWithTag(CLOUD_INDICATOR_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(LOCAL_INDICATOR_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule
            .onNode(
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
        composeRule
            .onNode(
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
    fun localOnlyPhotoShowsLocalIndicatorAndRemainsOpenable() {
        val localItem = mediaItem(isVideo = false).copy(
            mediaId = MediaId("local-42"),
            displayName = "local.jpg",
            assetRef = MediaAssetRef.LocalContent(
                contentUri = "content://media/external/images/media/42",
                modifiedAtEpochSeconds = null,
                sizeBytes = 1,
            ),
        )

        showSlot(mediaItem = localItem)

        composeRule
            .onNodeWithTag(CLOUD_INDICATOR_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule
            .onNodeWithTag(LOCAL_INDICATOR_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    LOCAL_COPY_DESCRIPTION,
                ) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
            ).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("local.jpg")
            .assertIsDisplayed()
    }

    @Test
    fun localAndRemotePhotoShowsAdjacentIndicatorsInTopRightCorner() {
        val remote = mediaItem(isVideo = false)
        val merged = remote.copy(
            mediaId = MediaId("local-42"),
            assetRef = MediaAssetRef.LocalFirst(
                local = MediaAssetRef.LocalContent(
                    contentUri = "content://media/external/images/media/42",
                    modifiedAtEpochSeconds = null,
                    sizeBytes = 1,
                ),
                remote = remote.assetRef as MediaAssetRef.MemoriesFile,
            ),
        )

        showSlot(mediaItem = merged)

        val localBounds = composeRule
            .onNodeWithTag(LOCAL_INDICATOR_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val cloudBounds = composeRule
            .onNodeWithTag(CLOUD_INDICATOR_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        val tile = composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "$LOCAL_COPY_DESCRIPTION, $CLOUD_COPY_DESCRIPTION",
                ) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
            ).assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        // Phone then cloud, side by side on one row.
        assertTrue("local=$localBounds cloud=$cloudBounds", localBounds.right <= cloudBounds.left)
        assertEquals(localBounds.center.y, cloudBounds.center.y, 1f)
        // Both sit in the tile's top-right quarter.
        listOf(localBounds, cloudBounds).forEach { indicator ->
            assertTrue("tile=$tile indicator=$indicator", indicator.left >= tile.center.x)
            assertTrue("tile=$tile indicator=$indicator", indicator.bottom <= tile.center.y)
        }
    }

    private fun showSlot(mediaItem: MediaItem?) {
        composeRule.setContent {
            MaterialTheme {
                MediaSlotTile(
                    slot = MediaSlot(
                        key = MediaSlotKey(dayId = DAY_ID, indexInDay = 0),
                        dayId = DAY_ID,
                        indexInDay = 0,
                        mediaItem = mediaItem,
                    ),
                    registerTimelineTile = { _, _ -> noOpUnregister },
                    onSelect = {},
                    requestFactory = MediaImageRequestFactory(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                        SessionStore(
                            object : CredentialsStore {
                                override fun load(): AccountCredentials = CREDENTIALS
                                override fun save(credentials: AccountCredentials) = Unit
                                override fun clear() = Unit
                            },
                        ),
                    ),
                )
            }
        }
    }

    private fun mediaItem(isVideo: Boolean): MediaItem =
        MediaItem(
            mediaId = MediaId("remote-42"),
            dayId = DAY_ID,
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
        const val LOCAL_INDICATOR_TAG = "local-device-indicator"
        val CLOUD_COPY_DESCRIPTION get() = string(R.string.media_cloud_copy)
        val LOCAL_COPY_DESCRIPTION get() = string(R.string.media_local_copy)
        val VIDEO_BADGE get() = string(R.string.media_video_badge)
        val CREDENTIALS = AccountCredentials(
            serverUrl = "https://cloud.example.com",
            loginName = "test",
            appPassword = "secret",
            userId = "test",
        )
        val noOpUnregister: () -> Unit = {}
    }
}

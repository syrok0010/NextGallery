package com.syrok0010.nextgallery.feature.collection

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.core.context.GlobalContext
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ViewerActionsTest {
    @get:Rule val rule = createComposeRule()
    private val items = mutableStateOf(listOf(first, middle, last))
    private val removed = mutableStateOf(emptySet<MediaId>())
    private val visible = mutableStateOf(false)
    private var received = emptyList<MediaItem>()

    @Test fun actionsReceiveOnlyCurrentLocalCloudOrMergedObject() {
        show()
        listOf(first, middle, last).forEachIndexed { index, item ->
            if (index == 0) {
                rule.onNodeWithContentDescription(item.displayName).performClick()
            } else {
                rule.onNodeWithTag("filmstrip_tile_$index").performClick()
            }
            rule.onNodeWithContentDescription("В альбом").performClick()
            rule.runOnIdle { assertEquals(listOf(item), received) }
        }
    }

    @Test fun removingMiddleShowsNextAndKeepsActionsUsable() {
        show()
        openAndRemove(middle)
        rule.onNodeWithText(last.displayName).assertIsDisplayed()
        rule.onNodeWithContentDescription("В альбом").performClick()
        rule.runOnIdle { assertEquals(listOf(last), received) }
    }

    @Test fun removingLastShowsPrevious() {
        show()
        openAndRemove(last)
        rule.onNodeWithText(middle.displayName).assertIsDisplayed()
    }

    @Test fun removingOnlyItemClosesViewer() {
        items.value = listOf(middle)
        show()
        openAndRemove(middle)
        rule.waitUntil { !visible.value }
        rule.onNodeWithContentDescription("В альбом").assertDoesNotExist()
    }

    @Test fun removingOneCopyKeepsCurrentIdentityAndUpdatedActions() {
        show()
        rule.onNodeWithContentDescription(last.displayName).performClick()
        val surviving = last.copy(assetRef = MediaAssetRef.MemoriesFile(3))
        rule.runOnIdle { items.value = listOf(first, middle, surviving) }
        rule.onNodeWithText(last.displayName).assertIsDisplayed()
        rule.onNodeWithContentDescription("В альбом").performClick()
        rule.runOnIdle { assertEquals(listOf(surviving), received) }
    }

    @Test fun temporaryDisappearanceWithoutSuccessfulDeletionRetainsCurrent() {
        show()
        rule.onNodeWithContentDescription(middle.displayName).performClick()
        rule.runOnIdle { items.value = listOf(first, last) }
        rule.onNodeWithText(middle.displayName).assertIsDisplayed()
    }

    @Test fun albumViewerHasNoActionsByDefault() {
        show(actionsEnabled = false)
        rule.onNodeWithContentDescription(middle.displayName).performClick()
        rule.onNodeWithText(middle.displayName).assertIsDisplayed()
        rule.onNodeWithContentDescription("В альбом").assertDoesNotExist()
        rule.onNodeWithContentDescription("Удалить").assertDoesNotExist()
    }

    private fun openAndRemove(item: MediaItem) {
        rule.onNodeWithContentDescription(item.displayName).performClick()
        rule.onNodeWithContentDescription("Удалить").performClick()
        rule.runOnIdle {
            assertEquals(listOf(item), received)
            // Index publication and completion can arrive in either order.
            items.value = items.value.filterNot { it.mediaId == item.mediaId }
        }
        rule.onNodeWithText(item.displayName).assertIsDisplayed()
        rule.runOnIdle { removed.value += item.mediaId }
        rule.waitForIdle()
    }

    private fun show(actionsEnabled: Boolean = true) {
        val imageKoin = koinApplication {
            modules(module {
                single<VideoPlayerFactory> { GlobalContext.get().get() }
                single {
                    MediaImageRequestFactory(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                        SessionStore(object : CredentialsStore {
                            override fun load() = AccountCredentials("http://127.0.0.1:1", "test", "unused", "test")
                            override fun save(credentials: AccountCredentials) = Unit
                            override fun clear() = Unit
                        }),
                    )
                }
            })
        }
        rule.setContent {
            KoinIsolatedContext(imageKoin) {
            NextGalleryTheme {
                val actions = if (actionsEnabled) {
                    listOf(
                        SelectionAction(
                            ColorPainter(Color.White),
                            "Удалить",
                        ) { selected, _ -> received = selected },
                        SelectionAction(ColorPainter(Color.White), "В альбом") { selected, done ->
                            received = selected
                            done()
                        },
                    )
                } else {
                    emptyList()
                }
                MediaCollectionScreen(
                    slots = items.value.mapIndexed { index, item ->
                        MediaSlot(MediaSlotKey(item.dayId, index), item.dayId, index, item)
                    },
                    emptyContent = {},
                    onViewerVisibilityChanged = { visible.value = it },
                    viewerActions = actions,
                    completedDeletions = removed.value,
                    scaffold = { _, content -> content() },
                )
            }
            }
        }
    }

    companion object {
        private val first = MediaItem(
            mediaId = MediaId("viewer-first"), dayId = 20660, displayName = "first.jpg",
            mimeType = "image/jpeg", width = 100, height = 100, etag = null, livePhotoId = null,
            auid = null, buid = null, sharedBy = null, takenAtEpochSeconds = null, isVideo = false,
            videoDurationSeconds = null, isFavorite = false, isHidden = false,
            assetRef = MediaAssetRef.LocalContent("content://viewer/first", null),
        )
        private val middle = first.copy(
            mediaId = MediaId("viewer-middle"),
            displayName = "middle.jpg",
            assetRef = MediaAssetRef.MemoriesFile(2),
        )
        private val last = first.copy(
            mediaId = MediaId("viewer-last"),
            displayName = "last.jpg",
            assetRef = MediaAssetRef.LocalFirst(
                MediaAssetRef.LocalContent("content://viewer/last", null),
                MediaAssetRef.MemoriesFile(3),
            ),
        )
    }
}

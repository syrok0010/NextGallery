package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.TimelineSlot
import com.syrok0010.nextgallery.data.memories.TimelineSlotKey
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineScrollAnchorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addingMediaAboveViewportKeepsVisibleMediaAtTheSameOffset() {
        val initialMedia = mediaRange()
        val anchor = initialMedia[4]

        assertRestoration(
            initialMedia = initialMedia,
            updatedMedia = listOf(
                mediaItem("newer", dayId = 20_001, takenAtEpochSeconds = 1_728_086_400L),
            ) + initialMedia,
            anchorMediaId = anchor.mediaId,
            expectedMediaId = anchor.mediaId,
        )
    }

    @Test
    fun removingMediaAboveViewportKeepsVisibleMediaAtTheSameOffset() {
        val retainedMedia = mediaRange()
        val anchor = retainedMedia[4]
        val removed = mediaItem("removed-newer", dayId = 20_001, takenAtEpochSeconds = 1_728_086_400L)

        assertRestoration(
            initialMedia = listOf(removed) + retainedMedia,
            updatedMedia = retainedMedia,
            anchorMediaId = anchor.mediaId,
            expectedMediaId = anchor.mediaId,
        )
    }

    @Test
    fun mergingAnchorCopiesKeepsVisibleMediaAtTheSameOffset() {
        val initialMedia = mediaRange().toMutableList()
        val anchorIndex = 4
        val local = initialMedia[anchorIndex].copy(
            remoteFileId = null,
            assetRef = MediaAssetRef.LocalContent(
                contentUri = "content://media/external/images/media/42",
                modifiedAtEpochSeconds = 1_728_000_100L,
            ),
        )
        initialMedia[anchorIndex] = local
        val updatedMedia = initialMedia.toMutableList()
        updatedMedia[anchorIndex] = local.copy(
            remoteFileId = 42,
            assetRef = MediaAssetRef.LocalFirst(
                local = local.assetRef as MediaAssetRef.LocalContent,
                remote = MediaAssetRef.MemoriesFile(42),
            ),
        )

        assertRestoration(
            initialMedia = initialMedia,
            updatedMedia = updatedMedia,
            anchorMediaId = local.mediaId,
            expectedMediaId = local.mediaId,
        )
    }

    @Test
    fun removedAnchorFallsBackToNearestMediaAtTheSameOffset() {
        val initialMedia = mediaRange()
        val anchor = initialMedia[4]
        val nearest = mediaItem(
            id = "nearest",
            dayId = anchor.dayId,
            takenAtEpochSeconds = requireNotNull(anchor.takenAtEpochSeconds) + 10,
        )
        val updatedMedia = initialMedia.toMutableList().apply {
            this[4] = nearest
        }

        assertRestoration(
            initialMedia = initialMedia,
            updatedMedia = updatedMedia,
            anchorMediaId = anchor.mediaId,
            expectedMediaId = nearest.mediaId,
        )
    }

    private fun assertRestoration(
        initialMedia: List<MediaItem>,
        updatedMedia: List<MediaItem>,
        anchorMediaId: MediaId,
        expectedMediaId: MediaId,
    ) {
        val gridItemsState = mutableStateOf(gridItems(initialMedia))
        val gridStateRef = AtomicReference<LazyGridState>()
        val scopeRef = AtomicReference<CoroutineScope>()

        composeRule.setContent {
            val gridState = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            val gridItems = gridItemsState.value
            SideEffect {
                gridStateRef.set(gridState)
                scopeRef.set(scope)
            }
            PreserveTimelineScrollAnchor(
                gridItems = gridItems,
                gridState = gridState,
                isScrollNavigationActive = false,
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                state = gridState,
                modifier = Modifier.size(width = 320.dp, height = 400.dp),
            ) {
                items(items = gridItems, key = TimelineGridItem::key) { item ->
                    Box(
                        modifier = Modifier.height(
                            if (item is TimelineGridItem.DayHeader) 32.dp else 96.dp,
                        ),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val initialGridItems = gridItemsState.value
        val anchorGridIndex = initialGridItems.indexOfMedia(anchorMediaId)
        composeRule.runOnIdle {
            scopeRef.get().launch {
                gridStateRef.get().scrollToItem(anchorGridIndex, 24)
            }
        }
        composeRule.waitUntil {
            gridStateRef.get().layoutInfo.visibleItemsInfo.any { it.key == "media:${anchorMediaId.value}" }
        }
        val offsetBefore = anchorViewportOffset(gridStateRef.get(), anchorMediaId)

        composeRule.runOnIdle {
            gridItemsState.value = gridItems(updatedMedia)
        }
        composeRule.waitUntil {
            val state = gridStateRef.get()
            state.layoutInfo.visibleItemsInfo.any { item ->
                item.key == "media:${expectedMediaId.value}" &&
                    item.index == gridItemsState.value.indexOfMedia(expectedMediaId)
            }
        }

        assertEquals(offsetBefore, anchorViewportOffset(gridStateRef.get(), expectedMediaId))
    }

    private fun mediaRange(): List<MediaItem> = (0 until 8).map { index ->
        mediaItem(
            id = "media-$index",
            dayId = 20_000 - index,
            takenAtEpochSeconds = 1_728_000_000L - index * 86_400L,
        )
    }

    private fun anchorViewportOffset(gridState: LazyGridState, mediaId: MediaId): Int {
        val item = gridState.layoutInfo.visibleItemsInfo.single { visibleItem ->
            visibleItem.key == "media:${mediaId.value}"
        }
        return item.offset.y - gridState.layoutInfo.viewportStartOffset
    }

    private fun gridItems(mediaItems: List<MediaItem>): List<TimelineGridItem> =
        mediaItems.mapIndexed { index, item ->
            TimelineSlot(
                key = TimelineSlotKey(dayId = item.dayId, indexInDay = index),
                dayId = item.dayId,
                indexInDay = index,
                mediaItem = item,
            )
        }.toTimelineGridItems()

    private fun List<TimelineGridItem>.indexOfMedia(mediaId: MediaId): Int =
        indexOfFirst { item ->
            item is TimelineGridItem.Slot && item.slot.mediaItem?.mediaId == mediaId
        }

    private fun mediaItem(
        id: String,
        dayId: Int,
        takenAtEpochSeconds: Long,
    ) = MediaItem(
        mediaId = MediaId(id),
        remoteFileId = id.hashCode().toLong(),
        dayId = dayId,
        day = LocalDate.ofEpochDay(dayId.toLong()),
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        width = 1_024,
        height = 768,
        etag = null,
        livePhotoId = null,
        auid = "auid-$id",
        buid = null,
        sharedBy = null,
        takenAtEpochSeconds = takenAtEpochSeconds,
        isVideo = false,
        videoDurationSeconds = null,
        isFavorite = false,
        isHidden = false,
        assetRef = MediaAssetRef.MemoriesFile(id.hashCode().toLong()),
    )
}

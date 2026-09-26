package com.syrok0010.nextgallery.feature.library

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.library.local.LocalMediaIndexState
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalMediaIndexerTest {
    @Test
    fun `permission controls local publication and regrant starts a new collection`() =
        runTest {
            val first = item(1)
            val second = item(2)
            var collections = 0
            val indexer = LocalMediaIndexer(
                updates = {
                    collections++
                    flowOf(
                        LocalMediaIndexState(
                            if (collections == 1) listOf(first) else listOf(second),
                            null,
                        ),
                    )
                },
                scope = backgroundScope,
            )

            indexer.updateAccess(LocalMediaPermissionMode.Full)
            runCurrent()
            assertEquals(listOf(first), indexer.items.value)

            indexer.updateAccess(LocalMediaPermissionMode.Denied)
            assertEquals(emptyList<MediaItem>(), indexer.items.value)

            indexer.updateAccess(LocalMediaPermissionMode.Full)
            runCurrent()
            assertEquals(listOf(second), indexer.items.value)
            assertEquals(2, collections)
        }

    @Test
    fun `refresh is forwarded only while full access is active`() =
        runTest {
            val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var collections = 0
            val indexer = LocalMediaIndexer(
                updates = { reconcileRequests ->
                    collections++
                    requests.tryEmit(Unit)
                    kotlinx.coroutines.flow.flow {
                        reconcileRequests.collect { emit(LocalMediaIndexState(emptyList(), null)) }
                    }
                },
                scope = backgroundScope,
            )

            indexer.refresh()
            assertEquals(0, collections)
            indexer.updateAccess(LocalMediaPermissionMode.Full)
            runCurrent()
            assertEquals(1, collections)
            indexer.refresh()
            runCurrent()
            assertEquals(1, collections)
        }

    private fun item(id: Long) =
        MediaItem(
            mediaId = MediaId("local-$id"),
            dayId = 1,
            displayName = "$id.jpg",
            mimeType = "image/jpeg",
            width = 1,
            height = 1,
            etag = null,
            livePhotoId = null,
            auid = null,
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = 1,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = MediaAssetRef.LocalContent("content://images/$id", 1),
        )
}

package com.syrok0010.nextgallery.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.data.local.LocalMediaBatch
import com.syrok0010.nextgallery.data.local.LocalMediaChangeObserver
import com.syrok0010.nextgallery.data.local.LocalMediaIndexProgress
import com.syrok0010.nextgallery.data.local.LocalMediaMetadata
import com.syrok0010.nextgallery.data.local.LocalMediaProjectionRepository
import com.syrok0010.nextgallery.data.local.LocalMediaReader
import com.syrok0010.nextgallery.data.local.LocalMediaSource
import com.syrok0010.nextgallery.data.memories.MediaIdentityCandidate
import com.syrok0010.nextgallery.data.memories.MediaIdentityRegistry
import com.syrok0010.nextgallery.data.memories.MediaIdentityResolution
import com.syrok0010.nextgallery.data.memories.UnifiedTimelineProjection
import com.syrok0010.nextgallery.domain.media.MediaSourceKind
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMediaIndexingRegressionTest {
    private lateinit var context: Context
    private lateinit var database: NextGalleryDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = Room.databaseBuilder(context, NextGalleryDatabase::class.java, DATABASE_NAME).build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun incrementalIndexingReconcilesEachIdentityOnceInRoom() = runBlocking {
        val registry = CountingMediaIdentityRegistry(RoomMediaIdentityRegistry(database))
        val source = LocalMediaSource(
            reader = LocalMediaReader {
                flowOf(
                    *Array(BATCH_COUNT) { batchIndex ->
                        val indexedCount = (batchIndex + 1) * BATCH_SIZE
                        LocalMediaBatch(
                            metadata = List(BATCH_SIZE) { itemIndex ->
                                metadata(batchIndex * BATCH_SIZE + itemIndex)
                            },
                            progress = LocalMediaIndexProgress(indexedCount, BATCH_COUNT * BATCH_SIZE),
                        )
                    },
                )
            },
            projectionStore = LocalMediaProjectionRepository(database),
            identityRegistry = registry,
            changeObserver = LocalMediaChangeObserver { emptyFlow() },
            batchSize = BATCH_SIZE,
        )
        val projection = UnifiedTimelineProjection()

        source.updates(emptyFlow()).take(BATCH_COUNT + 1).collect { state ->
            projection.replaceLocalItems(state.items)
        }

        assertEquals(BATCH_COUNT * BATCH_SIZE, registry.resolvedCandidateCount)
    }

    private fun metadata(index: Int) = LocalMediaMetadata(
        contentUri = "content://media/external/images/media/$index",
        displayName = "IMG_$index.jpg",
        mimeType = "image/jpeg",
        width = 4_032,
        height = 3_024,
        sizeBytes = 4_000_000,
        dateTakenMillis = 1_728_000_000_000L + index * 1_000L,
        memoriesTimelineEpochSeconds = null,
        imageUniqueId = null,
        dateModifiedSeconds = 1_728_000_000L + index,
        dateAddedSeconds = 1_728_000_000L + index,
        durationMillis = null,
        isVideo = false,
    )

    private class CountingMediaIdentityRegistry(
        private val delegate: MediaIdentityRegistry,
    ) : MediaIdentityRegistry {
        var resolvedCandidateCount = 0

        override suspend fun resolve(candidates: List<MediaIdentityCandidate>): MediaIdentityResolution {
            resolvedCandidateCount += candidates.size
            return delegate.resolve(candidates)
        }

        override suspend fun removeSource(source: MediaSourceKind) {
            delegate.removeSource(source)
        }
    }

    private companion object {
        const val DATABASE_NAME = "local-media-indexing-regression.db"
        const val BATCH_COUNT = 10
        const val BATCH_SIZE = 100
    }
}

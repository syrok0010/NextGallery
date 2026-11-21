package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VideoFilmstripItemStateTest {
    @Test fun placementUpdatesKeepExtractionAndCollapseCancelsIt() =
        runTest {
            val requests = mutableListOf<String>()
            var cancellations = 0
            val seeks = mutableListOf<Long>()
            val playback = object : FilmstripPlayback {
                override fun seek(id: MediaId, position: Long, finished: Boolean) {
                    seeks += position
                }
                override fun finish(id: MediaId) = Unit
            }
            val provider = VideoFrameProvider { uri ->
                flow {
                    requests += uri
                    try {
                        emit(VideoFrameEvent.Duration(10_001))
                        awaitCancellation()
                    } finally {
                        cancellations++
                    }
                }
            }
            val state =
                VideoFilmstripItemState(
                    mediaItem("video", 0),
                    provider,
                    playback,
                    backgroundScope,
                ) { 0L }
            val expanded = VideoFilmstripPlacement(true, true, true, 0f, false)
            state.update(expanded.copy(expanded = false))
            runCurrent()
            assertTrue(requests.isEmpty())
            state.update(expanded)
            runCurrent()
            assertEquals(listOf("content://filmstrip/video"), requests)
            state.update(expanded)
            runCurrent()
            assertEquals(0, cancellations)
            assertEquals(listOf("content://filmstrip/video"), requests)
            var playhead = 0f
            assertTrue(state.seek(0.5f) { playhead = it })
            assertEquals(0.5f, playhead)
            assertEquals(listOf(5000L), seeks)
            state.update(expanded.copy(expanded = false))
            runCurrent()
            assertEquals(1, cancellations)
            assertFalse(state.seek(0.7f) {})
            state.update(expanded)
            runCurrent()
            assertEquals(2, requests.size)
            state.close()
            runCurrent()
            assertEquals(2, cancellations)
        }

    @Test fun disposingDuringScrubSendsFinalSeekAndFinishesPlayback() =
        runTest {
            val seeks = mutableListOf<Pair<Long, Boolean>>()
            var finished = 0
            var cancellations = 0
            val playback = object : FilmstripPlayback {
                override fun seek(id: MediaId, position: Long, finished: Boolean) {
                    seeks += position to finished
                }
                override fun finish(id: MediaId) {
                    finished++
                }
            }
            val provider = VideoFrameProvider {
                flow {
                    try {
                        emit(VideoFrameEvent.Duration(10_001))
                        awaitCancellation()
                    } finally {
                        cancellations++
                    }
                }
            }
            val state =
                VideoFilmstripItemState(
                    mediaItem("video", 0),
                    provider,
                    playback,
                    backgroundScope,
                ) { 0L }
            val placement = VideoFilmstripPlacement(true, true, true, 0.2f, false)
            state.update(placement)
            runCurrent()
            state.update(placement.copy(scrolling = true))
            // This position falls within the seek throttle, but still needs a final seek on disposal.
            state.update(placement.copy(scrolling = true, fraction = 0.7f))
            assertEquals(listOf(2000L to false), seeks)
            state.close()
            runCurrent()
            assertEquals(listOf(2000L to false, 7000L to true), seeks)
            assertEquals(1, finished)
            assertEquals(1, cancellations)
            state.close()
            assertEquals(1, finished)
        }

    private fun mediaItem(id: String, dayId: Int) =
        MediaItem(
            mediaId = MediaId(id),
            dayId = dayId,
            displayName = "$id.jpg",
            mimeType = "image/jpeg",
            width = 1_024,
            height = 768,
            etag = null,
            livePhotoId = null,
            auid = "auid-$id",
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = 1_728_000_000L,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = MediaAssetRef.LocalContent(
                contentUri = "content://filmstrip/$id",
                modifiedAtEpochSeconds = 1_728_000_000L,
                sizeBytes = 1,
            ),
        )
}

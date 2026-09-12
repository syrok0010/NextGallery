package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VideoFilmstripItemStateTest {
    @Test fun sourceChangeAndCollapseCancelExtractionAndReopenStartsFresh() = runTest {
        val requests = mutableListOf<String>()
        var cancellations = 0
        var activeSource: String? = null
        val seeks = mutableListOf<Long>()
        val playback = object : FilmstripPlayback {
            override fun sourceFor(id: MediaId) = activeSource
            override fun seek(id: MediaId, position: Long, finished: Boolean) { seeks += position }
            override fun finish(id: MediaId) = Unit
        }
        val provider = VideoFrameProvider { uri ->
            flow {
                requests += uri
                try {
                    emit(VideoFrameEvent.Duration(10_001))
                    awaitCancellation()
                } finally { cancellations++ }
            }
        }
        val state = VideoFilmstripItemState(mediaItem("video", 0), provider, playback, backgroundScope) { 0L }
        val expanded = VideoFilmstripPlacement(true, true, true, 400.dp, 0f, false)
        state.update(expanded.copy(expanded = false))
        runCurrent()
        assertTrue(requests.isEmpty())
        state.update(expanded)
        runCurrent()
        assertEquals(listOf("content://filmstrip/video"), requests)
        activeSource = "https://cloud.example/apps/memories/api/video/transcode/client123/42/360p.m3u8"
        state.update(expanded)
        runCurrent()
        assertEquals(1, cancellations)
        assertEquals(activeSource, requests.last())
        var playhead = 0f
        assertTrue(state.seek(0.5f) { playhead = it })
        assertEquals(0.5f, playhead)
        assertEquals(listOf(5000L), seeks)
        state.update(expanded.copy(expanded = false))
        runCurrent()
        assertEquals(2, cancellations)
        assertFalse(state.seek(0.7f) {})
        state.update(expanded)
        runCurrent()
        assertEquals(3, requests.size)
        state.close()
        runCurrent()
        assertEquals(3, cancellations)
    }

    private fun mediaItem(
        id: String,
        dayId: Int,
    ) = MediaItem(
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
        ),
    )
}

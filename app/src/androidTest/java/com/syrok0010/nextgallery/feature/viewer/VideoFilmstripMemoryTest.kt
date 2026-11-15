package com.syrok0010.nextgallery.feature.viewer

import android.graphics.Bitmap
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class VideoFilmstripMemoryTest {
    @Test fun completedFramesSurviveNewLoaderButPartialAndCancelledLoadsRetry() = runBlocking {
        val cache = VideoFilmstripCache<Bitmap>(1024 * 1024) { it.allocationByteCount }
        val key = VideoFrameCacheKey(MediaAssetRef.MemoriesFile(42), "v1")
        var loads = 0
        var complete = false
        val provider = VideoFrameProvider { flow {
            loads++
            emit(VideoFrameEvent.Duration(1000))
            emit(VideoFrameEvent.Frame(0, Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)))
            if (!complete) awaitCancellation()
            repeat(2) { emit(VideoFrameEvent.Frame(it + 1, Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))) }
        } }
        val cancelled = VideoFilmstripLoader(provider, "original", this, cache, key)
        cancelled.retry()
        yield()
        cancelled.close()
        yield()
        assertNull(cache[key])
        complete = true
        val first = VideoFilmstripLoader(provider, "original", this, cache, key)
        first.retry()
        yield()
        assertEquals(VideoFilmstripPhase.Ready, first.state.phase)
        first.close()
        val reopened = VideoFilmstripLoader(provider, "different-playback-quality", this, cache, key)
        reopened.retry()
        yield()
        assertEquals(2, loads)
        assertEquals(VideoFilmstripPhase.Ready, reopened.state.phase)
        assertSame(first.state.frames[0], reopened.state.frames[0])
        reopened.close()
        val revised = VideoFilmstripLoader(provider, "original", this, cache, key.copy(etag = "v2"))
        revised.retry()
        yield()
        assertEquals(3, loads)
        revised.close()
    }
}

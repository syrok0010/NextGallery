package com.syrok0010.nextgallery.feature.viewer

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VideoFilmstripLoaderTest {
    @Test fun retryCancelsPreviousExtractionAndCloseReleasesCurrentRequest() = runTest {
        var started = 0
        var cancelled = 0
        val loader = VideoFilmstripLoader(VideoFrameProvider {
            flow {
                started++
                try {
                    emit(VideoFrameEvent.Duration(12_000))
                    awaitCancellation()
                } finally { cancelled++ }
            }
        }, "source", backgroundScope)
        loader.retry()
        runCurrent()
        loader.retry()
        runCurrent()
        assertEquals(2, started)
        assertEquals(1, cancelled)
        assertEquals(VideoFilmstripPhase.Loading, loader.state.phase)
        loader.close()
        runCurrent()
        assertEquals(2, cancelled)
        assertEquals(VideoFilmstripPhase.Loading, loader.state.phase)
    }

    @Test fun failedExtractionCanRetryWithoutLosingAbilityToScrub() = runTest {
        var attempts = 0
        val loader = VideoFilmstripLoader(VideoFrameProvider {
            flow {
                attempts++
                if (attempts == 1) throw java.io.IOException("temporary")
                emit(VideoFrameEvent.Duration(10_001))
                awaitCancellation()
            }
        }, "source", backgroundScope)
        loader.retry()
        runCurrent()
        assertEquals(VideoFilmstripPhase.Degraded, loader.state.phase)
        loader.retry()
        runCurrent()
        assertEquals(VideoFilmstripPhase.Loading, loader.state.phase)
        val seeks = mutableListOf<Pair<Long, Boolean>>()
        var finished = 0
        val seek: (Long, Boolean) -> Unit = { position, final -> seeks += position to final }
        loader.scroll(0.2f, true, 0, seek) { finished++ }
        loader.scroll(0.3f, true, 50, seek) { finished++ }
        loader.scroll(0.4f, false, 60, seek) { finished++ }
        loader.scroll(0.4f, false, 70, seek) { finished++ }
        assertEquals(listOf(2000L to false, 4000L to true), seeks)
        assertEquals(1, finished)
        loader.close()
    }
}

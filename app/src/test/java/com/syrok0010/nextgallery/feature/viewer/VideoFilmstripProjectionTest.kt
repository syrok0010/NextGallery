package com.syrok0010.nextgallery.feature.viewer

import org.junit.Assert.*
import org.junit.Test

class VideoFilmstripProjectionTest {
    @Test fun `poster first and logarithmic frame count with evenly spaced positions`() {
        for ((duration, expectedCount) in listOf(
            1L to 3, 999L to 3, 1000L to 3, 1999L to 3,
            10_000L to 7, 30_000L to 10, 60_000L to 12,
            300_000L to 17, 3_600_000L to 24, 86_400_000L to 34,
        )) {
            val projection = VideoFilmstripProjection<String>()
            assertTrue(projection.state.showsPoster)
            projection.durationKnown(duration)
            val positions = projection.state.positionsMillis
            assertEquals(expectedCount, positions.size)
            assertEquals(0L, positions.first())
            assertEquals(duration - 1, positions.last())
            val intervals = positions.zipWithNext { a, b -> b - a }
            assertTrue(intervals.max() - intervals.min() <= 1)
            projection.frameReady(0, "first")
            assertEquals(mapOf(0 to "first"), projection.state.frames)
            assertTrue(projection.state.showsPoster)
            for (index in 1 until expectedCount) projection.frameReady(index, "frame-$index")
            projection.finished()
            assertEquals(VideoFilmstripPhase.Ready, projection.state.phase)
            assertFalse(projection.state.showsPoster)
        }
    }

    @Test fun `degraded frames remain visible and retry resets bounded work`() {
        val projection = VideoFilmstripProjection<String>()
        projection.durationKnown(12_000)
        projection.frameReady(0, "first")
        projection.frameReady(projection.state.positionsMillis.size, "outside plan")
        projection.failed()
        assertEquals(VideoFilmstripPhase.Degraded, projection.state.phase)
        assertEquals(mapOf(0 to "first"), projection.state.frames)
        projection.retry()
        assertTrue(projection.state.showsPoster)
        assertEquals(VideoFilmstripPhase.Loading, projection.state.phase)
        assertTrue(projection.state.frames.isEmpty())
        projection.durationKnown(12_000)
        projection.finished()
        assertEquals(VideoFilmstripPhase.Degraded, projection.state.phase)
    }

    @Test fun `scrub throttles seeks clamps position and always sends final position`() {
        val projection = VideoFilmstripProjection<String>()
        assertNull(projection.scrub(0.5f, 0))
        projection.durationKnown(10_001)
        assertEquals(2000L, projection.scrub(0.2f, 1000))
        assertNull(projection.scrub(0.5f, 1050))
        assertEquals(7000L, projection.scrub(0.7f, 1100))
        assertEquals(8000L, projection.scrub(0.8f, 1110, finished = true))
        assertEquals(0L, projection.scrub(-1f, 1111))
        assertEquals(10_000L, projection.scrub(2f, 1112, finished = true))
        assertNull(projection.scrub(Float.NaN, 1300))
    }

    @Test fun `silent scrub prepares poster and preserves pause until explicit play`() {
        val session = VideoPlaybackSession("content://video/1")
        assertEquals(VideoPlaybackEffect.SilentSeek(5000, "content://video/1"),
            session.accept(VideoPlaybackInput.ScrubTo(5000)))
        assertFalse(session.state.playRequested)
        session.accept(VideoPlaybackInput.PlayerChanged(VideoPlaybackPhase.Paused, 12_000))
        assertEquals(VideoPlaybackEffect.FinishScrub(false, 1f), session.accept(VideoPlaybackInput.EndScrub))
        assertEquals(5000L, session.state.positionMillis)
        assertEquals(VideoPlaybackEffect.Play, session.accept(VideoPlaybackInput.Play))
    }

    @Test fun `scrub pauses playback and retains mute after release`() {
        val session = VideoPlaybackSession("content://video/1", "https://cloud.example/nextcloud/apps/memories/api/stream/1")
        session.accept(VideoPlaybackInput.Play)
        session.accept(VideoPlaybackInput.ToggleMute)
        session.accept(VideoPlaybackInput.ScrubTo(4000))
        assertEquals(VideoPlaybackEffect.PrepareSource("https://cloud.example/nextcloud/apps/memories/api/stream/1", 4000, false),
            session.accept(VideoPlaybackInput.SourceFailed(VideoPlaybackError.CannotPlay)))
        assertEquals(VideoPlaybackEffect.FinishScrub(false, 0f), session.accept(VideoPlaybackInput.EndScrub))
        session.accept(VideoPlaybackInput.ScrubTo(6000))
        session.accept(VideoPlaybackInput.Pause)
        assertEquals(VideoPlaybackEffect.FinishScrub(false, 0f), session.accept(VideoPlaybackInput.EndScrub))
    }
}

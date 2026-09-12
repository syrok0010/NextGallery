package com.syrok0010.nextgallery.feature.viewer

import org.junit.Assert.*
import org.junit.Test

class VideoFilmstripProjectionTest {
    @Test fun `poster first and 24 evenly spaced frames regardless of duration`() {
        for (duration in listOf(1L, 12_000L, 86_400_000L)) {
            val projection = VideoFilmstripProjection<String>()
            assertTrue(projection.state.showsPoster)
            projection.durationKnown(duration)
            val positions = projection.state.positionsMillis
            assertEquals(24, positions.size)
            assertEquals(0L, positions.first())
            assertEquals(duration - 1, positions.last())
            val intervals = positions.zipWithNext { a, b -> b - a }
            assertTrue(intervals.max() - intervals.min() <= 1)
            projection.frameReady(0, "first")
            assertEquals(mapOf(0 to "first"), projection.state.frames)
            assertTrue(projection.state.showsPoster)
            for (index in 1..23) projection.frameReady(index, "frame-$index")
            projection.finished()
            assertEquals(VideoFilmstripPhase.Ready, projection.state.phase)
            assertFalse(projection.state.showsPoster)
        }
    }

    @Test fun `degraded frames remain visible and retry resets bounded work`() {
        val projection = VideoFilmstripProjection<String>()
        projection.durationKnown(12_000)
        projection.frameReady(0, "first")
        projection.frameReady(24, "outside plan")
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
        session.accept(VideoPlaybackInput.PlayerReady(12_000))
        assertEquals(VideoPlaybackEffect.FinishScrub(false, 1f), session.accept(VideoPlaybackInput.EndScrub))
        assertEquals(5000L, session.state.positionMillis)
        assertEquals(VideoPlaybackEffect.Play, session.accept(VideoPlaybackInput.Play))
    }

    @Test fun `scrub pauses playback and retains mute after release`() {
        val session = VideoPlaybackSession("content://video/1", "https://memories.invalid/original/1")
        session.accept(VideoPlaybackInput.Play)
        session.accept(VideoPlaybackInput.ToggleMute)
        session.accept(VideoPlaybackInput.ScrubTo(4000))
        assertEquals(VideoPlaybackEffect.PrepareAndPlay("https://memories.invalid/original/1", 4000, false),
            session.accept(VideoPlaybackInput.PlayerFailed))
        assertEquals(VideoPlaybackEffect.FinishScrub(false, 0f), session.accept(VideoPlaybackInput.EndScrub))
        session.accept(VideoPlaybackInput.ScrubTo(6000))
        session.accept(VideoPlaybackInput.Pause)
        assertEquals(VideoPlaybackEffect.FinishScrub(false, 0f), session.accept(VideoPlaybackInput.EndScrub))
        session.accept(VideoPlaybackInput.Leave)
        assertEquals(VideoPlaybackState(), session.state)
    }
}

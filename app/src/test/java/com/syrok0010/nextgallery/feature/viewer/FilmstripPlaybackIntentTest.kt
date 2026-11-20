package com.syrok0010.nextgallery.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmstripPlaybackIntentTest {
    @Test fun `filmstrip resumes a playing video after repeated seeks`() {
        val session = VideoPlaybackSession("content://video/42")
        session.accept(VideoPlaybackInput.Play)
        session.accept(VideoPlaybackInput.PlayerChanged(VideoPlaybackPhase.Playing, 60_000))
        session.accept(VideoPlaybackInput.ScrubTo(10_000))
        session.accept(VideoPlaybackInput.PlayerChanged(VideoPlaybackPhase.Paused))
        session.accept(VideoPlaybackInput.ScrubTo(20_000))
        val effect = session.accept(VideoPlaybackInput.EndScrub) as VideoPlaybackEffect.FinishScrub
        assertTrue("Filmstrip must keep playback intent", effect.playWhenReady)
        assertEquals(20_000L, session.state.positionMillis)
    }

    @Test fun `scrub keeps play intent while the player is buffering`() {
        val session = VideoPlaybackSession("content://video/42")
        session.accept(VideoPlaybackInput.Play)
        session.accept(VideoPlaybackInput.ScrubTo(10_000))
        val effect = session.accept(VideoPlaybackInput.EndScrub) as VideoPlaybackEffect.FinishScrub
        assertTrue(effect.playWhenReady)
    }
}

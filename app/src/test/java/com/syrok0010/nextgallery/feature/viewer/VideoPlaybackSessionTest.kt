package com.syrok0010.nextgallery.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlaybackSessionTest {
    @Test
    fun `play from poster starts loading the local video`() {
        val session = VideoPlaybackSession("content://media/video/42")

        val effect = session.accept(VideoPlaybackInput.Play)

        assertEquals(VideoPlaybackPhase.Loading, session.state.phase)
        assertEquals(
            VideoPlaybackEffect.PrepareAndPlay("content://media/video/42"),
            effect,
        )
    }

    @Test
    fun `ready player exposes duration without starting playback`() {
        val session = VideoPlaybackSession("content://media/video/42")
        session.accept(VideoPlaybackInput.Play)

        val effect = session.accept(VideoPlaybackInput.PlayerReady(durationMillis = 3_500))

        assertEquals(VideoPlaybackPhase.Ready, session.state.phase)
        assertEquals(3_500L, session.state.durationMillis)
        assertEquals(0L, session.state.positionMillis)
        assertEquals(null, effect)
    }

    @Test
    fun `player playback state and pause action stay in sync`() {
        val session = VideoPlaybackSession("content://media/video/42")
        session.accept(VideoPlaybackInput.Play)
        session.accept(VideoPlaybackInput.PlayerReady(durationMillis = 3_500L))

        assertEquals(null, session.accept(VideoPlaybackInput.PlayerIsPlaying(true)))
        assertEquals(VideoPlaybackPhase.Playing, session.state.phase)

        val effect = session.accept(VideoPlaybackInput.Pause)

        assertEquals(VideoPlaybackPhase.Paused, session.state.phase)
        assertEquals(VideoPlaybackEffect.Pause, effect)

        assertEquals(VideoPlaybackEffect.Play, session.accept(VideoPlaybackInput.Play))
        assertEquals(VideoPlaybackPhase.Paused, session.state.phase)
    }

    @Test
    fun `player ready does not reset position after a rebuffer`() {
        val session = VideoPlaybackSession("content://media/video/42")
        session.accept(VideoPlaybackInput.Play)
        session.accept(VideoPlaybackInput.PlayerReady(durationMillis = 3_500L))
        session.accept(VideoPlaybackInput.PlayerPositionChanged(1_200L))

        session.accept(VideoPlaybackInput.PlayerReady(durationMillis = 3_500L))

        assertEquals(1_200L, session.state.positionMillis)
    }

    @Test
    fun `play after ended explicitly restarts from the beginning`() {
        val session = VideoPlaybackSession("content://media/video/42")
        session.accept(VideoPlaybackInput.Play)
        session.accept(VideoPlaybackInput.PlayerReady(durationMillis = 3_500L))
        session.accept(VideoPlaybackInput.PlayerPositionChanged(3_500L))
        session.accept(VideoPlaybackInput.PlayerIsPlaying(false))

        assertEquals(VideoPlaybackEffect.ReplayFromStart, session.accept(VideoPlaybackInput.Play))
    }

    @Test
    fun `seek and mute update playback state and player effects`() {
        val session = VideoPlaybackSession("content://media/video/42")
        session.accept(VideoPlaybackInput.Play)
        session.accept(VideoPlaybackInput.PlayerReady(durationMillis = 3_500L))

        assertEquals(
            VideoPlaybackEffect.SeekTo(1_200L),
            session.accept(VideoPlaybackInput.SeekTo(1_200L)),
        )
        assertEquals(1_200L, session.state.positionMillis)

        assertEquals(VideoPlaybackEffect.SetVolume(0f), session.accept(VideoPlaybackInput.ToggleMute))
        assertEquals(true, session.state.isMuted)
    }

    @Test
    fun `error can be retried and leaving releases the session`() {
        val session = VideoPlaybackSession("content://media/video/42")
        session.accept(VideoPlaybackInput.Play)

        session.accept(VideoPlaybackInput.PlayerFailed)
        assertEquals(VideoPlaybackPhase.Error, session.state.phase)
        assertEquals(VideoPlaybackError.CannotPlay, session.state.error)

        assertEquals(
            VideoPlaybackEffect.PrepareAndPlay("content://media/video/42"),
            session.accept(VideoPlaybackInput.Retry),
        )
        assertEquals(VideoPlaybackPhase.Loading, session.state.phase)

        assertEquals(VideoPlaybackEffect.PauseAndRelease, session.accept(VideoPlaybackInput.Leave))
        assertEquals(VideoPlaybackPhase.Poster, session.state.phase)
        assertEquals(0L, session.state.positionMillis)
    }

    @Test
    fun `player progress and fullscreen are represented in session state`() {
        val session = VideoPlaybackSession("content://media/video/42")
        session.accept(VideoPlaybackInput.Play)
        session.accept(VideoPlaybackInput.PlayerReady(durationMillis = 3_500L))

        session.accept(VideoPlaybackInput.PlayerPositionChanged(850L))
        session.accept(VideoPlaybackInput.EnterFullscreen)

        assertEquals(850L, session.state.positionMillis)
        assertEquals(true, session.state.isFullscreen)

        session.accept(VideoPlaybackInput.ExitFullscreen)
        assertEquals(false, session.state.isFullscreen)
    }
}

package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.feature.viewer.playback.RemoteVideoQuality
import org.junit.Assert.*
import org.junit.Test

class VideoQualityTest {
    private val remote = "https://cloud.example/nextcloud/apps/memories/api/stream/42"
    private val auto = RemoteVideoQuality("Auto", "https://cloud.example/nextcloud/apps/memories/api/video/transcode/client123/42/index.m3u8", isAdaptive = true)
    private val low = RemoteVideoQuality("360p", "https://cloud.example/nextcloud/apps/memories/api/video/transcode/client123/42/360p.m3u8")

    @Test fun `remote decode failure tries HLS once and retry preserves intent and position`() {
        val session = VideoPlaybackSession(remote, remoteUri = remote)
        session.accept(VideoPlaybackInput.QualitiesLoaded(listOf(auto, low)))
        session.accept(VideoPlaybackInput.Play)
        session.accept(VideoPlaybackInput.PlayerPositionChanged(4000))
        session.accept(VideoPlaybackInput.Pause)
        val fallback = session.accept(VideoPlaybackInput.SourceFailed(VideoPlaybackError.CannotPlay)) as VideoPlaybackEffect.PrepareSource
        assertEquals(auto.uri, fallback.contentUri)
        assertEquals(4000, fallback.positionMillis)
        assertFalse(fallback.playWhenReady)
        assertNull(session.accept(VideoPlaybackInput.SourceFailed(VideoPlaybackError.CannotPlay)))
        assertEquals(VideoPlaybackError.TranscodeFailed, session.state.error)
        assertEquals(fallback, session.accept(VideoPlaybackInput.Retry))
    }

    @Test fun `quality override moves local first video to remote without changing paused position`() {
        val session = VideoPlaybackSession("content://video/42", remote)
        session.accept(VideoPlaybackInput.QualitiesLoaded(listOf(auto, low)))
        assertEquals("content://video/42", (session.accept(VideoPlaybackInput.Play) as VideoPlaybackEffect.PrepareSource).contentUri)
        session.accept(VideoPlaybackInput.PlayerPositionChanged(5000))
        session.accept(VideoPlaybackInput.Pause)
        val effect = session.accept(VideoPlaybackInput.SelectQuality(low)) as VideoPlaybackEffect.PrepareSource
        assertEquals(low.uri, effect.contentUri)
        assertEquals(5000, effect.positionMillis)
        assertFalse(effect.playWhenReady)
        assertEquals(VideoPlaybackPhase.Loading, session.state.phase)
        assertEquals("360p", session.state.quality)
    }

    @Test fun `unavailable VOD and local only expose no quality menu`() {
        for (session in listOf(VideoPlaybackSession(remote, remoteUri = remote), VideoPlaybackSession("content://video/42"))) {
            session.accept(VideoPlaybackInput.QualitiesLoaded(emptyList()))
            assertTrue(session.state.qualities.isEmpty())
            assertNull(session.accept(VideoPlaybackInput.SelectQuality(low)))
            session.accept(VideoPlaybackInput.Play)
            assertNull(session.accept(VideoPlaybackInput.SourceFailed(VideoPlaybackError.CannotPlay)))
        }
    }

    @Test fun `authentication and network failures never try HLS`() {
        for (error in listOf(VideoPlaybackError.AuthenticationRequired, VideoPlaybackError.RemoteUnavailable)) {
            val session = VideoPlaybackSession(remote, remoteUri = remote)
            session.accept(VideoPlaybackInput.QualitiesLoaded(listOf(auto)))
            session.accept(VideoPlaybackInput.Play)
            assertNull(session.accept(VideoPlaybackInput.SourceFailed(error)))
            assertEquals(error, session.state.error)
        }
    }

    @Test fun `HLS authentication and network errors retain their category and retry intent`() {
        for (error in listOf(VideoPlaybackError.AuthenticationRequired, VideoPlaybackError.RemoteUnavailable)) {
            val session = VideoPlaybackSession(remote, remoteUri = remote)
            session.accept(VideoPlaybackInput.QualitiesLoaded(listOf(auto)))
            session.accept(VideoPlaybackInput.Play)
            session.accept(VideoPlaybackInput.PlayerPositionChanged(4500))
            session.accept(VideoPlaybackInput.Pause)
            session.accept(VideoPlaybackInput.SelectQuality(auto))
            assertNull(session.accept(VideoPlaybackInput.SourceFailed(error)))
            assertEquals(error, session.state.error)
            assertEquals(VideoPlaybackEffect.PrepareSource(auto.uri, 4500, false), session.accept(VideoPlaybackInput.Retry))
        }
    }

    @Test fun `local remote HLS ladder retains playing intent`() {
        val session = VideoPlaybackSession("content://video/42", remote)
        session.accept(VideoPlaybackInput.QualitiesLoaded(listOf(auto)))
        session.accept(VideoPlaybackInput.Play)
        session.accept(VideoPlaybackInput.PlayerPositionChanged(3000))
        assertEquals(VideoPlaybackEffect.PrepareSource(remote, 3000, true), session.accept(VideoPlaybackInput.SourceFailed(VideoPlaybackError.CannotPlay)))
        assertEquals(VideoPlaybackEffect.PrepareSource(auto.uri, 3000, true), session.accept(VideoPlaybackInput.SourceFailed(VideoPlaybackError.CannotPlay)))
        assertNull(session.accept(VideoPlaybackInput.SourceFailed(VideoPlaybackError.CannotPlay)))
    }
    @Test fun `source policy does not depend on quality labels`() {
        val session = VideoPlaybackSession(remote, remoteUri = remote)
        val adaptive = auto.copy(label = "Adaptive")
        val variant = low.copy(label = "Direct")
        session.accept(VideoPlaybackInput.QualitiesLoaded(listOf(adaptive, variant)))
        session.accept(VideoPlaybackInput.Play)
        assertEquals(adaptive.uri,
            (session.accept(VideoPlaybackInput.SourceFailed(VideoPlaybackError.CannotPlay)) as VideoPlaybackEffect.PrepareSource).contentUri)
        session.accept(VideoPlaybackInput.SelectQuality(variant))
        session.accept(VideoPlaybackInput.SourceFailed(VideoPlaybackError.CannotPlay))
        assertEquals(VideoPlaybackError.TranscodeFailed, session.state.error)
        assertEquals(variant.uri, (session.accept(VideoPlaybackInput.Retry) as VideoPlaybackEffect.PrepareSource).contentUri)
    }

}

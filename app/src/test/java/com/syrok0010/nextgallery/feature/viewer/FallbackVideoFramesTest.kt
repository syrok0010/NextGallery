package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.feature.viewer.playback.RemoteVideoQuality
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FallbackVideoFramesTest {
    private val original = "https://memories.invalid/original/42"

    @Test fun `readable local frames never request remote or capabilities`() = runBlocking {
        val provider = FallbackVideoFrames(
            local = VideoFrameProvider { flow { emit(VideoFrameEvent.Duration(12_000)) } },
            remote = VideoFrameProvider { error("Remote requested") }, fallbackUri = original,
            qualities = { error("Discovery requested") },
        )
        assertEquals(listOf(VideoFrameEvent.Duration(12_000)), provider.frames("content://local/42").toList())
    }

    @Test fun `local failure retries remote independently and explicit retry reopens it`() = runBlocking {
        val requests = mutableListOf<String>()
        var offline = true
        val provider = FallbackVideoFrames(
            local = VideoFrameProvider { flow { throw IOException("Local unavailable") } },
            remote = VideoFrameProvider { uri -> flow {
                requests += uri
                if (offline) throw IOException("Offline")
                emit(VideoFrameEvent.Duration(12_000))
            } }, fallbackUri = original, qualities = { error("Network errors must not transcode") },
        )
        try { provider.frames("content://local/42").toList(); fail() } catch (_: IOException) { }
        offline = false
        assertEquals(listOf(VideoFrameEvent.Duration(12_000)), provider.frames("content://local/42").toList())
        assertEquals(listOf(original, original), requests)
    }

    @Test fun `unsupported original uses only discovered HLS and stops after HLS error`() = runBlocking {
        val requests = mutableListOf<String>()
        val hls = "https://memories.invalid/vod/client123/42/index.m3u8"
        val provider = FallbackVideoFrames(
            local = VideoFrameProvider { error("Local requested") },
            remote = VideoFrameProvider { uri -> flow {
                requests += uri
                throw UnsupportedVideoFrames(IOException("Unsupported format"))
            } }, fallbackUri = null,
            qualities = { listOf(RemoteVideoQuality("Auto", hls)) },
        )
        try { provider.frames(original).toList(); fail() } catch (_: UnsupportedVideoFrames) { }
        assertEquals(listOf(original, hls), requests)
    }

    @Test fun `cancelled local extraction never starts remote fallback`() = runBlocking {
        val provider = FallbackVideoFrames(
            local = VideoFrameProvider { flow { throw CancellationException("Page left") } },
            remote = VideoFrameProvider { error("Remote requested") }, fallbackUri = original,
            qualities = { error("Discovery requested") },
        )
        try { provider.frames("content://local/42").toList(); fail() } catch (_: CancellationException) { }
    }
}

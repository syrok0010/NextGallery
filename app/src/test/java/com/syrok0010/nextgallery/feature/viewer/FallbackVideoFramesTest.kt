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
    private val original = "https://cloud.example/apps/memories/api/stream/42"
    private val low = RemoteVideoQuality("480p", "$original/480p.m3u8", bitrate = 500_000)
    private val high = RemoteVideoQuality("1080p", "$original/1080p.m3u8", bitrate = 5_000_000)

    @Test fun `readable local frames never request remote or capabilities`() = runBlocking {
        val provider = FallbackVideoFrames(
            local = VideoFrameProvider { flow { emit(VideoFrameEvent.Duration(12_000)) } },
            remote = VideoFrameProvider { error("Remote requested") }, fallbackUri = original,
            qualities = { error("Discovery requested") },
        )
        assertEquals(listOf(VideoFrameEvent.Duration(12_000)), provider.frames("content://local/42").toList())
    }

    @Test fun `remote and broken local prefer the cheapest fixed stream`() = runBlocking {
        for (uri in listOf(original, "content://local/42")) {
            val requests = mutableListOf<String>()
            val provider = FallbackVideoFrames(
                local = VideoFrameProvider { flow { throw IOException("Local unavailable") } },
                remote = VideoFrameProvider { source -> flow {
                    requests += source
                    emit(VideoFrameEvent.Duration(12_000))
                } }, fallbackUri = original,
                qualities = { listOf(high, RemoteVideoQuality("Auto", "$original/index.m3u8", isAdaptive = true), low) },
            )
            provider.frames(uri).toList()
            assertEquals(listOf(low.uri), requests)
        }
    }

    @Test fun `unavailable VOD uses original without adaptive or stream copy extraction`() = runBlocking {
        for (variants in listOf(emptyList(), listOf(
            RemoteVideoQuality("Auto", "$original/index.m3u8", isAdaptive = true),
            low.copy(label = "Original", isOriginal = true),
        ))) {
            val requests = mutableListOf<String>()
            val provider = FallbackVideoFrames(
                local = VideoFrameProvider { error("Local requested") },
                remote = VideoFrameProvider { source -> flow { requests += source } },
                fallbackUri = null, qualities = { variants },
            )
            provider.frames(original).toList()
            assertEquals(listOf(original), requests)
        }
    }

    @Test fun `discovery failure does not prevent readable original frames`() = runBlocking {
        val requests = mutableListOf<String>()
        val provider = FallbackVideoFrames(
            local = VideoFrameProvider { error("Local requested") },
            remote = VideoFrameProvider { source -> flow { requests += source } },
            fallbackUri = null, qualities = { throw IOException("VOD unavailable") },
        )
        provider.frames(original).toList()
        assertEquals(listOf(original), requests)
    }

    @Test fun `failed low quality tries original once and explicit retry rediscovers`() = runBlocking {
        val requests = mutableListOf<String>()
        var discoveries = 0
        val provider = FallbackVideoFrames(
            local = VideoFrameProvider { error("Local requested") },
            remote = VideoFrameProvider { source -> flow {
                requests += source
                if (source == low.uri) throw IOException("Cold transcode failed")
                emit(VideoFrameEvent.Duration(12_000))
            } }, fallbackUri = null, qualities = { discoveries++; listOf(low) },
        )
        repeat(2) { provider.frames(original).toList() }
        assertEquals(listOf(low.uri, original, low.uri, original), requests)
        assertEquals(2, discoveries)
    }

    @Test fun `failed original stops without retrying high quality or adaptive streams`() = runBlocking {
        val requests = mutableListOf<String>()
        val provider = FallbackVideoFrames(
            local = VideoFrameProvider { error("Local requested") },
            remote = VideoFrameProvider { source -> flow {
                requests += source
                throw UnsupportedVideoFrames(IOException("Unsupported"))
            } }, fallbackUri = null, qualities = { listOf(high, low) },
        )
        try { provider.frames(original).toList(); fail() } catch (_: IOException) { }
        assertEquals(listOf(low.uri, original), requests)
    }

    @Test fun `cancellation never starts another source`() = runBlocking {
        for (stage in listOf("local", "discovery", "remote")) {
            val requests = mutableListOf<String>()
            val provider = FallbackVideoFrames(
                local = VideoFrameProvider { flow { throw CancellationException("Page left") } },
                remote = VideoFrameProvider { source -> flow {
                    requests += source
                    throw CancellationException("Page left")
                } }, fallbackUri = original, qualities = {
                    if (stage == "discovery") throw CancellationException("Page left")
                    listOf(low)
                },
            )
            try { provider.frames(if (stage == "local") "content://local/42" else original).toList(); fail() }
            catch (_: CancellationException) { }
            assertEquals(if (stage == "remote") listOf(low.uri) else emptyList(), requests)
        }
    }
}

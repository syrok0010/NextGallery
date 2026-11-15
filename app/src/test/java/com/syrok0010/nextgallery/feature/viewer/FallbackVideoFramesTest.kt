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

    @Test fun `remote and broken local use readable original without discovery`() = runBlocking {
        for (uri in listOf(original, "content://local/42")) {
            val requests = mutableListOf<String>()
            val provider = FallbackVideoFrames(
                local = VideoFrameProvider { flow { throw IOException("Local unavailable") } },
                remote = VideoFrameProvider { source -> flow {
                    requests += source
                    emit(VideoFrameEvent.Duration(12_000))
                } }, fallbackUri = original,
                qualities = { error("Readable original must not start discovery") },
            )
            assertEquals(listOf(VideoFrameEvent.Duration(12_000)), provider.frames(uri).toList())
            assertEquals(listOf(original), requests)
        }
    }

    @Test fun `unsupported original tries cheapest fixed HLS and retry starts from original`() = runBlocking {
        val requests = mutableListOf<String>()
        var discoveries = 0
        val provider = FallbackVideoFrames(
            local = VideoFrameProvider { error("Local requested") },
            remote = VideoFrameProvider { source -> flow {
                requests += source
                if (source == original) throw UnsupportedVideoFrames(IOException("Unsupported"))
                emit(VideoFrameEvent.Duration(12_000))
            } }, fallbackUri = null, qualities = {
                discoveries++
                listOf(high, RemoteVideoQuality("Auto", "$original/index.m3u8", isAdaptive = true), low)
            },
        )
        repeat(2) { provider.frames(original).toList() }
        assertEquals(listOf(original, low.uri, original, low.uri), requests)
        assertEquals(2, discoveries)
    }

    @Test fun `unavailable VOD preserves unsupported original without adaptive or stream copy extraction`() = runBlocking {
        for (variants in listOf(emptyList(), listOf(
            RemoteVideoQuality("Auto", "$original/index.m3u8", isAdaptive = true),
            low.copy(label = "Original", isOriginal = true),
        ))) {
            val requests = mutableListOf<String>()
            val unsupported = UnsupportedVideoFrames(IOException("Unsupported"))
            val provider = FallbackVideoFrames(
                local = VideoFrameProvider { error("Local requested") },
                remote = VideoFrameProvider { source -> flow { requests += source; throw unsupported } },
                fallbackUri = null, qualities = { variants },
            )
            try { provider.frames(original).toList(); fail() } catch (failure: IOException) { assertSame(unsupported, failure) }
            assertEquals(listOf(original), requests)
        }
    }

    @Test fun `original network and timeout failures never start HLS discovery`() = runBlocking {
        for (failure in listOf(IOException("503"), IOException("Frame extraction timed out"))) {
            val requests = mutableListOf<String>()
            val provider = FallbackVideoFrames(
                local = VideoFrameProvider { error("Local requested") },
                remote = VideoFrameProvider { source -> flow { requests += source; throw failure } },
                fallbackUri = null, qualities = { error("Network failure must not start transcode") },
            )
            try { provider.frames(original).toList(); fail() } catch (actual: IOException) { assertSame(failure, actual) }
            assertEquals(listOf(original), requests)
        }
    }

    @Test fun `failed HLS stops without repeating original or trying another quality`() = runBlocking {
        val requests = mutableListOf<String>()
        val hlsFailure = IOException("Cold transcode failed")
        val provider = FallbackVideoFrames(
            local = VideoFrameProvider { error("Local requested") },
            remote = VideoFrameProvider { source -> flow {
                requests += source
                if (source == original) throw UnsupportedVideoFrames(IOException("Unsupported"))
                throw hlsFailure
            } }, fallbackUri = null, qualities = { listOf(high, low) },
        )
        try { provider.frames(original).toList(); fail() } catch (actual: IOException) { assertSame(hlsFailure, actual) }
        assertEquals(listOf(original, low.uri), requests)
    }

    @Test fun `failed discovery does not retry unsupported original`() = runBlocking {
        val requests = mutableListOf<String>()
        val discoveryFailure = IOException("Discovery unavailable")
        val provider = FallbackVideoFrames(
            local = VideoFrameProvider { error("Local requested") },
            remote = VideoFrameProvider { source -> flow {
                requests += source
                throw UnsupportedVideoFrames(IOException("Unsupported"))
            } }, fallbackUri = null, qualities = { throw discoveryFailure },
        )
        try { provider.frames(original).toList(); fail() } catch (actual: IOException) { assertSame(discoveryFailure, actual) }
        assertEquals(listOf(original), requests)
    }

    @Test fun `cancellation never starts another source`() = runBlocking {
        for (stage in listOf("local", "original", "discovery", "hls")) {
            val requests = mutableListOf<String>()
            val provider = FallbackVideoFrames(
                local = VideoFrameProvider { flow { throw CancellationException("Page left") } },
                remote = VideoFrameProvider { source -> flow {
                    requests += source
                    if (stage == "original" || source == low.uri) throw CancellationException("Page left")
                    throw UnsupportedVideoFrames(IOException("Unsupported"))
                } }, fallbackUri = original, qualities = {
                    if (stage == "discovery") throw CancellationException("Page left")
                    listOf(low)
                },
            )
            try { provider.frames(if (stage == "local") "content://local/42" else original).toList(); fail() }
            catch (_: CancellationException) { }
            assertEquals(when (stage) {
                "local" -> emptyList()
                "hls" -> listOf(original, low.uri)
                else -> listOf(original)
            }, requests)
        }
    }
}

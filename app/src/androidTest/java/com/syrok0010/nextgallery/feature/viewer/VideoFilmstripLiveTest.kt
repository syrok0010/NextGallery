package com.syrok0010.nextgallery.feature.viewer

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import com.syrok0010.nextgallery.feature.viewer.playback.lowestForFrames
import com.syrok0010.nextgallery.feature.viewer.playback.newVodClientId
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Opt-in measurement on disposable files: no playback or HLS warmup. */
class VideoFilmstripLiveTest {
    @Test fun compareOriginalFirstWithColdHlsExperiment() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val ids = args.getString("filmstripFileIds")
            assumeTrue("Live test requires disposable testcloud file IDs", !ids.isNullOrBlank())
            val server = checkNotNull(args.getString("filmstripServer"))
            require(server == "http://test.syrok")
            val traffic = Traffic()
            val transport = NextcloudTransport(
                Json { ignoreUnknownKeys = true },
                NextcloudTransport.defaultBaseClient().newBuilder().eventListener(traffic).build(),
            )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val factory =
                VideoPlayerFactory(
                    transport,
                    AccountCredentials(
                        server,
                        "test",
                        checkNotNull(args.getString("filmstripPassword")),
                        "test",
                    ),
                )
            for (id in checkNotNull(ids).split(',').map(String::toLong)) {
                val sources = factory.sources(MediaAssetRef.MemoriesFile(id))
                val original = measure(
                    VideoFramesFactory(context, factory).create(sources),
                    sources.primary,
                    traffic,
                )
                assertTrue(
                    "Original-first must not discover or request HLS: ${traffic.paths}",
                    traffic.paths.none {
                        it.contains(
                            "/video/transcode/",
                        ) ||
                            it.endsWith("/config")
                    },
                )
                val remote = RemoteVideoFrames(context) { factory.mediaSourceFactory(context) }
                // Deliberately retain the rejected HLS-first policy here as an experiment.
                val experiment = VideoFrameProvider { uri ->
                    flow {
                        val quality = checkNotNull(
                            factory
                                .qualities(
                                    checkNotNull(sources.remoteOriginal),
                                    newVodClientId(),
                                ).lowestForFrames(),
                        ) { "Experiment requires a fixed HLS profile" }
                        try {
                            emitAll(remote.frames(quality.uri))
                        } catch (_: IOException) {
                            emitAll(remote.frames(uri))
                        }
                    }
                }
                val low =
                    measure(
                        experiment,
                        sources.primary,
                        traffic,
                    )
                Log.i(
                    "FilmstripMeasurement",
                    JSONObject()
                        .put("fileId", id)
                        .put("originalFirst", original)
                        .put("coldLowestHls", low)
                        .put("originalFallback", traffic.paths.any { it.contains("/stream/") })
                        .toString(),
                )
                assertEquals(original.getInt("frames"), low.getInt("frames"))
                assertTrue(
                    "Expected a fixed HLS variant: ${traffic.paths}",
                    traffic.paths.any { it.endsWith("p.m3u8") },
                )
                val expectedFallback = args
                    .getString("filmstripFallbackIds", "")
                    .split(',')
                    .contains(id.toString())
                assertEquals(
                    "Unexpected original fallback: ${traffic.paths}",
                    expectedFallback,
                    traffic.paths.any { it.contains("/stream/") },
                )
            }
        }

    private suspend fun measure(
        provider: VideoFrameProvider,
        uri: String,
        traffic: Traffic,
    ): JSONObject {
        traffic.bytes.set(0)
        traffic.paths.clear()
        val start = SystemClock.elapsedRealtime()
        var firstFrame: Long? = null
        val indices = mutableSetOf<Int>()
        var duration = 0L
        provider.frames(uri).collect { event ->
            when (event) {
                is VideoFrameEvent.Duration -> {
                    duration = event.millis
                    indices.clear()
                }

                is VideoFrameEvent.Frame -> {
                    if (firstFrame == null) firstFrame = SystemClock.elapsedRealtime() - start
                    indices += event.index
                }
            }
        }
        assertEquals(videoFilmstripFrameCount(duration), indices.size)
        return JSONObject()
            .put("durationMs", duration)
            .put("frames", indices.size)
            .put("firstFrameMs", firstFrame)
            .put("readyMs", SystemClock.elapsedRealtime() - start)
            .put("bodyBytes", traffic.bytes.get())
            .put("requests", traffic.paths.size)
    }

    private class Traffic : EventListener() {
        val bytes = AtomicLong()
        val paths = CopyOnWriteArrayList<String>()
        override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
            paths += request.url.encodedPath
        }
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            bytes.addAndGet(byteCount)
        }
    }
}

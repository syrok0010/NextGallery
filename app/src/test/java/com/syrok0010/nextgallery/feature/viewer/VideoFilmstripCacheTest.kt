package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import org.junit.Assert.*
import org.junit.Test

class VideoFilmstripCacheTest {
    private fun key(id: Long) = VideoFrameCacheKey(MediaAssetRef.MemoriesFile(id), "v1")
    private fun ready() = VideoFilmstripState(
        positionsMillis = listOf(0L, 500L, 999L),
        frames = mapOf(0 to "aa", 1 to "bb", 2 to "cc"),
        phase = VideoFilmstripPhase.Ready,
    )

    @Test fun `byte budget evicts least recently read strip and replacement is counted once`() {
        val cache = VideoFilmstripCache<String>(12) { it.length }
        cache.put(key(1), ready())
        cache.put(key(2), ready())
        assertNotNull(cache[key(1)])
        cache.put(key(3), ready())
        assertNull(cache[key(2)])
        cache.put(key(1), ready())
        assertNotNull(cache[key(1)])
        assertNotNull(cache[key(3)])
    }

    @Test fun `incomplete failed and oversized results do not displace completed strips`() {
        val cache = VideoFilmstripCache<String>(6) { it.length }
        cache.put(key(1), ready())
        cache.put(key(2), ready().copy(frames = mapOf(0 to "aa")))
        cache.put(key(3), ready().copy(phase = VideoFilmstripPhase.Degraded))
        cache.put(key(4), ready().copy(frames = mapOf(0 to "aaaa", 1 to "bbbb", 2 to "cccc")))
        assertNotNull(cache[key(1)])
        for (id in 2L..4L) assertNull(cache[key(id)])
    }

    @Test fun `remote revision and local modification are separate keys and clear drops everything`() {
        val cache = VideoFilmstripCache<String>(12) { it.length }
        cache.put(key(1), ready())
        assertNull(cache[key(1).copy(etag = "v2")])
        val local = VideoFrameCacheKey(MediaAssetRef.LocalContent("content://video/1", 123, 100), null)
        cache.put(local, ready())
        assertNull(cache[local.copy(asset = MediaAssetRef.LocalContent("content://video/1", 124, 100))])
        cache.clear()
        assertNull(cache[key(1)])
        assertNull(cache[local])
    }
}

package com.syrok0010.nextgallery.feature.viewer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VodClientIdTest {
    private val original = RemoteVideoOriginal(42, "https://cloud.example")

    @Test
    fun `client id is the hex form Memories 10 accepts`() {
        val clientId = newVodClientId()

        assertTrue(clientId.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(clientId, newVodClientId())
        assertEquals(
            "https://cloud.example/apps/memories/api/video/transcode/$clientId/42/index.m3u8",
            original.masterPlaylist(clientId),
        )
    }

    @Test
    fun `dashed UUID is rejected before Memories answers 400`() {
        assertThrows(IllegalArgumentException::class.java) {
            original.masterPlaylist("123e4567-e89b-12d3-a456-426614174000")
        }
    }
}

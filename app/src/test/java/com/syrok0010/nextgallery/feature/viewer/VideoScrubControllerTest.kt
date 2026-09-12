package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.core.media.MediaId
import org.junit.Assert.*
import org.junit.Test

class VideoScrubControllerTest {
    @Test fun sourceAndCommandsBelongOnlyToTheControllersMedia() {
        val current = MediaId("current")
        val neighbor = MediaId("neighbor")
        val controller = VideoScrubController(current)
        val commands = mutableListOf<VideoPlaybackInput>()
        controller.dispatch = { commands += it }
        controller.sourceUri = "https://memories.invalid/360p.m3u8"

        assertNull(controller.sourceFor(neighbor))
        controller.seek(neighbor, 500, true)
        controller.finish(neighbor)
        assertTrue(commands.isEmpty())

        assertEquals(controller.sourceUri, controller.sourceFor(current))
        controller.seek(current, 700, true)
        assertEquals(listOf(VideoPlaybackInput.ScrubTo(700), VideoPlaybackInput.EndScrub), commands)

        controller.dispatch = null
        controller.seek(current, 900, true)
        assertEquals(2, commands.size)
    }
}

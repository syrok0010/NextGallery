package com.syrok0010.nextgallery.feature.viewer.playback

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MemoriesVideoManifestTest {
    private val master = "https://cloud.example/nextcloud/apps/memories/api/video/transcode/client123/42/index.m3u8"

    @Test fun `qualities come only from manifest including original and portrait resolution`() {
        val result = MemoriesVideoManifest.qualities(master, """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=1920x1080
            max.m3u8?session=123
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=360x640
            360p.m3u8
        """.trimIndent())
        assertEquals(listOf("Auto", "Original", "360p"), result.map { it.label })
        assertTrue(result[1].uri.endsWith("max.m3u8?session=123"))
    }

    @Test fun `HLS attributes resolve variable references and quoted codec lists`() {
        val result = MemoriesVideoManifest.qualities(master, """
            #EXTM3U
            #EXT-X-DEFINE:NAME="quality",VALUE="720p"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1280x720
            {${'$'}quality}.m3u8?session=123
        """.trimIndent())
        assertEquals(listOf("Auto", "720p"), result.map { it.label })
        assertTrue(result.last().uri.endsWith("/720p.m3u8?session=123"))
    }

    @Test fun `empty media playlists and untrusted variants do not invent qualities`() {
        assertTrue(MemoriesVideoManifest.qualities(master, "#EXTM3U\n#EXTINF:12,\nfile.ts").isEmpty())
        for (uri in listOf("https://evil.example/360p.m3u8", "../360p.m3u8", "https://user@cloud.example/nextcloud/apps/memories/api/video/transcode/client123/42/360p.m3u8", "http://cloud.example/nextcloud/apps/memories/api/video/transcode/client123/42/360p.m3u8")) {
            assertTrue(MemoriesVideoManifest.qualities(master, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360\n$uri").isEmpty())
        }
        assertThrows(IOException::class.java) { MemoriesVideoManifest.qualities(master, "<html>error</html>") }
    }
}

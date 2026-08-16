package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.domain.media.MediaId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoriesDtoMappingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `decode describe response`() {
        val dto = json.decodeFromString<MemoriesDescribeDto>(fixture("describe.json"))

        assertEquals("7.5.2", dto.version)
        assertEquals("https://cloud.example.com/apps/memories/", dto.baseUrl)
        assertEquals("https://cloud.example.com/index.php/login/v2", dto.loginFlowUrl)
        assertEquals("alice", dto.uid)
    }

    @Test
    fun `decode config response and map selected capabilities`() {
        val dto = json.decodeFromString<MemoriesConfigDto>(fixture("config.json"))

        val config = dto.toMemoriesConfig()

        assertEquals("7.5.2", config.version)
        assertEquals("/Photos", config.timelinePath)
        assertTrue(config.albumsEnabled)
        assertFalse(config.recognizeEnabled)
        assertTrue(config.previewGeneratorEnabled)
        assertTrue(config.stackRawFiles)
        assertFalse(config.dedupIdentical)
    }

    @Test
    fun `decode day response with preloaded detail`() {
        val days = json.decodeFromString<List<MemoriesDayDto>>(fixture("days.json"))

        assertEquals(1, days.size)
        assertEquals(19870, days.single().dayid)
        assertEquals(1, days.single().count)
        assertEquals(42L, days.single().detail.single().fileid)
    }

    @Test
    fun `decode day photos response with image and video`() {
        val photos = json.decodeFromString<List<MemoriesPhotoDto>>(fixture("day-photos.json"))

        assertEquals(2, photos.size)
        assertEquals("IMG_0042.jpg", photos[0].basename)
        assertEquals("VID_0043.mp4", photos[1].basename)
    }

    @Test
    fun `map video response with resolved media identity`() {
        val photo = json.decodeFromString<List<MemoriesPhotoDto>>(fixture("day-photos.json"))[1]

        val item = photo.toMediaItem(MediaId("resolved-media"))
        val memoriesAsset = item.assetRef as MediaAssetRef.MemoriesFile
        val imageUrls = MemoriesAssetUrlFactory.urlsFor(
            assetRef = memoriesAsset,
            serverUrl = "https://cloud.example.com/",
        )

        assertEquals(43L, item.remoteFileId)
        assertEquals(MediaId("resolved-media"), item.mediaId)
        assertEquals("VID_0043.mp4", item.displayName)
        assertEquals("video/mp4", item.mimeType)
        assertTrue(item.isVideo)
        assertTrue(item.isFavorite)
        assertTrue(item.isHidden)
        assertEquals(12L, item.videoDurationSeconds)
        assertEquals(MediaAssetRef.MemoriesFile(photoFileId = 43L), item.assetRef)
        assertEquals("https://cloud.example.com/apps/memories/api/image/preview/43?x=512&y=512&a=1", imageUrls.thumbnailUrl)
        assertEquals("https://cloud.example.com/apps/memories/api/image/preview/43?x=1600&y=1600&a=1", imageUrls.detailPreviewUrl)
        assertEquals("https://cloud.example.com/apps/memories/api/stream/43", imageUrls.originalUrl)
    }

    private fun fixture(name: String): String {
        return checkNotNull(javaClass.classLoader?.getResource("memories/$name")) {
            "Missing fixture: $name"
        }.readText()
    }
}

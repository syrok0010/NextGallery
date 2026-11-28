package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.network.memories.MemoriesAlbumDto
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AlbumCatalogTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `same names and matching covers do not merge containers`() {
        val local = localAlbumSummaries(
            listOf(
                localItem("1", "external_primary", "Pictures/Отпуск/", "content://1", 9),
                localItem("2", "external_primary", "Pictures/Отпуск/", "content://2", 8),
                localItem("3", "sd", "Pictures/Отпуск/", "content://3", 7),
                localItem("4", "external_primary", "Download/Отпуск/", "content://4", 6),
            ),
        )
        val remote =
            remoteAlbumSummary(
                json.decodeFromString<MemoriesAlbumDto>(
                    """{"album_id":1,"name":"Отпуск","count":"3","cover":"2","cover_etag":"v2","user":"anna"}""",
                ),
            )
        val result = albumCatalog(listOf(remote, remote.copy(id = "remote:2")), local)
        assertEquals(5, result.size)
        assertEquals(5, result.map { it.id }.toSet().size)
        assertEquals(2, local.first().count)
        assertEquals("Pictures/Отпуск", local.first().detail)
        assertEquals("Pictures/Отпуск · sd", local.first { it.id.contains(":sd:") }.detail)
        assertEquals(AlbumCover.Local("content://1", 9), local.first().cover)
        assertEquals("anna", remote.detail)
        assertEquals(AlbumCover.Remote(2, "v2"), remote.cover)
    }

    @Test fun `cover falls back to last added and unknown counts stay unknown`() {
        val result =
            remoteAlbumSummary(
                json.decodeFromString<MemoriesAlbumDto>(
                    """{"album_id":3,"name":"Empty","cover":0,"last_added_photo":"42","last_added_photo_etag":"v3","count":null,"user_display":"Анна"}""",
                ),
            )
        assertEquals(AlbumCover.Remote(42, "v3"), result.cover)
        assertNull(result.count)
        assertEquals("Анна", result.detail)
        assertNull(
            remoteAlbumSummary(
                json.decodeFromString<MemoriesAlbumDto>(
                    """{"album_id":4,"name":"Empty","last_added_photo":0,"count":0}""",
                ),
            ).cover,
        )
    }

    private fun localItem(id: String, volume: String, path: String, uri: String, modified: Long) =
        MediaItem(
            mediaId = MediaId(id),
            dayId = 1,
            displayName = "$id.jpg",
            mimeType = "image/jpeg",
            width = 10,
            height = 10,
            etag = null,
            livePhotoId = null,
            auid = null,
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = modified,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = MediaAssetRef.LocalContent(
                contentUri = uri,
                modifiedAtEpochSeconds = modified,
                sizeBytes = 1,
                volumeName = volume,
                relativePath = path,
            ),
        )
}

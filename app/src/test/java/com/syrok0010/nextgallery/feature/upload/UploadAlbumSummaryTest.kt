package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.feature.albums.AlbumCopyOutcome
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UploadAlbumSummaryTest {
    @Test fun `summary counts files already in album as added and reports failures`() {
        assertEquals(
            listOf(AlbumUploadSummary("Лето", added = 2, failed = 1)),
            uploadAlbumSummaries(
                mapOf(
                    "Лето" to listOf(
                        AlbumCopyOutcome.Added,
                        AlbumCopyOutcome.AlreadyPresent,
                        AlbumCopyOutcome.Failed,
                    ),
                ),
            ),
        )
        assertTrue(uploadAlbumSummaries(emptyMap()).isEmpty())
    }

    @Test fun `upload URL uses the uid and encodes the file name as one segment`() {
        val url = uploadUrl("https://cloud.example/", "anna", "/Photos/Camera/", "Лето #1?.jpg")
        assertEquals(
            "/remote.php/dav/files/anna/Photos/Camera/%D0%9B%D0%B5%D1%82%D0%BE%20%231%3F.jpg",
            url.encodedPath,
        )
        assertEquals("Лето #1?.jpg", url.pathSegments.last())
        assertNull(url.query)
    }

    @Test fun `selection without album keeps the PR 91 payload format`() {
        val legacy = """[{"contentUri":"content://media/1","displayName":"a.jpg","mimeType":"image/jpeg"}]"""
        assertEquals(
            listOf(UploadSelection("content://media/1", "a.jpg", "image/jpeg")),
            Json.decodeFromString<List<UploadSelection>>(legacy),
        )
        val withAlbum = UploadSelection("content://media/1", "a.jpg", null, UploadAlbum("anna", "Лето"))
        assertEquals(
            withAlbum,
            Json.decodeFromString<UploadSelection>(Json.encodeToString(withAlbum)),
        )
    }
}

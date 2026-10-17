package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.feature.albums.AlbumCopyOutcome
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UploadAlbumSummaryTest {
    @Test fun `summary counts files already in album as added and reports failures`() {
        assertEquals(
            "В «Лето»: 2, не добавлено: 1",
            uploadAlbumSummary(
                mapOf(
                    "Лето" to listOf(
                        AlbumCopyOutcome.Added,
                        AlbumCopyOutcome.AlreadyPresent,
                        AlbumCopyOutcome.Failed,
                    ),
                ),
            ),
        )
        assertNull(uploadAlbumSummary(emptyMap()))
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

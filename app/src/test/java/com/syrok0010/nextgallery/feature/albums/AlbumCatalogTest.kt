package com.syrok0010.nextgallery.feature.albums

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AlbumCatalogTest {
    @Test fun `same names and matching covers do not merge containers`() {
        val local = localAlbumSummaries(
            sequenceOf(
                LocalAlbumEntry("external_primary", "Pictures/Отпуск/", "content://1", 9),
                LocalAlbumEntry("external_primary", "Pictures/Отпуск/", "content://2", 8),
                LocalAlbumEntry("sd", "Pictures/Отпуск/", "content://3", 7),
                LocalAlbumEntry("external_primary", "Download/Отпуск/", "content://4", 6),
            ),
        )
        val remote =
            remoteAlbumSummary(
                Json
                    .parseToJsonElement(
                        """{"album_id":1,"name":"Отпуск","count":"3","cover":"2","cover_etag":"v2","user":"anna"}""",
                    ).jsonObject,
            )
        val result = albumCatalog(listOf(remote, remote.copy(id = "remote:2")), local)
        assertEquals(5, result.size)
        assertEquals(5, result.map { it.id }.toSet().size)
        assertEquals(2, local.first().count)
        assertEquals(AlbumCover.Local("content://1", 9), local.first().cover)
        assertEquals("anna", remote.detail)
        assertEquals(AlbumCover.Remote(2, "v2"), remote.cover)
    }

    @Test fun `cover falls back to last added and unknown counts stay unknown`() {
        val result =
            remoteAlbumSummary(
                Json
                    .parseToJsonElement(
                        """{"album_id":3,"name":"Empty","cover":0,"last_added_photo":"42","last_added_photo_etag":"v3","count":null,"user_display":"Анна"}""",
                    ).jsonObject,
            )
        assertEquals(AlbumCover.Remote(42, "v3"), result.cover)
        assertNull(result.count)
        assertEquals("Анна", result.detail)
        assertNull(
            remoteAlbumSummary(
                Json
                    .parseToJsonElement(
                        """{"album_id":4,"name":"Empty","last_added_photo":0,"count":0}""",
                    ).jsonObject,
            ).cover,
        )
    }
}

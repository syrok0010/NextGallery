package com.syrok0010.nextgallery.feature.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadDestinationTest {
    @Test
    fun `timeline paths preserve list order and trim whitespace`() {
        assertEquals(listOf("/Photos", "/Camera"), timelinePaths("  /Photos ; /Camera  "))
    }

    @Test
    fun `empty entries are discarded before choosing the first directory`() {
        assertEquals(listOf("/Photos", "/Camera"), timelinePaths("; ; /Photos ;; /Camera ; "))
    }

    @Test
    fun `single path and files root are valid destinations`() {
        assertEquals(listOf("/Photos"), timelinePaths("/Photos"))
        assertEquals(listOf("/", "/Photos"), timelinePaths(" / ; /Photos "))
        assertEquals(
            "https://cloud.example/remote.php/dav/files/test/a.jpg",
            uploadUrl("https://cloud.example", "test", timelinePaths("/").first(), "a.jpg").toString(),
        )
    }

    @Test
    fun `missing or entirely empty list has no destination`() {
        listOf(null, "", " ", "; ; ;").forEach { assertEquals(emptyList<String>(), timelinePaths(it)) }
    }

    @Test
    fun `upload uses only the first directory with independently encoded path segments`() {
        val destination = timelinePaths(" ; /Фото 100%/a?#/literal%2F ; /Camera ").first()
        val url = uploadUrl("https://cloud.example/nextcloud", "user@example", destination, "name /?#%.jpg")

        assertEquals(
            listOf("nextcloud", "remote.php", "dav", "files", "user@example", "Фото 100%", "a?#", "literal%2F", "name /?#%.jpg"),
            url.pathSegments,
        )
        assertEquals(
            "/nextcloud/remote.php/dav/files/user@example/%D0%A4%D0%BE%D1%82%D0%BE%20100%25/a%3F%23/literal%252F/name%20%2F%3F%23%25.jpg",
            url.encodedPath,
        )
        assertEquals(null, url.query)
        assertEquals(null, url.fragment)
    }
}

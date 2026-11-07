package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.network.dav.DavFilePath
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
            DavFilePath("test", listOf("a.jpg")),
            DavFilePath.of("test", timelinePaths("/").first(), "a.jpg"),
        )
    }

    @Test
    fun `missing or entirely empty list has no destination`() {
        listOf(
            null,
            "",
            " ",
            "; ; ;",
        ).forEach { assertEquals(emptyList<String>(), timelinePaths(it)) }
    }

    @Test
    fun `upload uses only the first directory split into path segments`() {
        val destination = timelinePaths(" ; /Фото 100%/a?#/literal%2F ; /Camera ").first()
        assertEquals(
            DavFilePath(
                "user@example",
                listOf("Фото 100%", "a?#", "literal%2F", "name /?#%.jpg"),
            ),
            DavFilePath.of("user@example", destination, "name /?#%.jpg"),
        )
    }
}

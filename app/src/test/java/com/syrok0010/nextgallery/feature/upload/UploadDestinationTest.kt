package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadDestinationTest {
    @Test
    fun `upload goes to the first timeline path without surrounding whitespace`() {
        assertEquals("/Photos", uploadDestination("  /Photos ; /Camera  "))
    }

    @Test
    fun `empty entries are skipped before choosing the first directory`() {
        assertEquals("/Photos", uploadDestination("; ; /Photos ;; /Camera ; "))
    }

    @Test
    fun `files root is a valid destination`() {
        assertEquals("/", uploadDestination(" / ; /Photos "))
        assertEquals(
            DavFilePath("test", listOf("a.jpg")),
            DavFilePath.of("test", uploadDestination("/")!!, "a.jpg"),
        )
    }

    @Test
    fun `missing or entirely empty list has no destination`() {
        listOf(null, "", " ", "; ; ;").forEach { assertNull(uploadDestination(it)) }
    }

    @Test
    fun `destination splits into path segments that keep special characters`() {
        val destination = uploadDestination(" ; /Фото 100%/a?#/literal%2F ; /Camera ")!!
        assertEquals(
            DavFilePath(
                "user@example",
                listOf("Фото 100%", "a?#", "literal%2F", "name /?#%.jpg"),
            ),
            DavFilePath.of("user@example", destination, "name /?#%.jpg"),
        )
    }
}

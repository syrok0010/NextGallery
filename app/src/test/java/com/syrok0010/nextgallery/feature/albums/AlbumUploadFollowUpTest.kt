package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.upload.UploadAlbum
import com.syrok0010.nextgallery.feature.upload.UploadJob
import com.syrok0010.nextgallery.feature.upload.UploadSelection
import com.syrok0010.nextgallery.feature.upload.UploadedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumUploadFollowUpTest {
    @Test fun `first attempt counts a conflict in an existing album as added`() {
        val (added, methods) = run(recovered = false, members = emptyList(), copy = 409)
        assertTrue(added)
        assertEquals(listOf("COPY", "PROPFIND 0"), methods)
    }

    @Test fun `recovered attempt finds the file in the album and never copies again`() {
        val (added, methods) = run(recovered = true, members = listOf(FILE_ID), copy = 201)
        assertTrue(added)
        assertEquals(listOf("PROPFIND 1"), methods)
    }

    @Test fun `recovered attempt copies a file missing from the album`() {
        val (added, methods) = run(recovered = true, members = listOf(FILE_ID + 1), copy = 201)
        assertTrue(added)
        assertEquals(listOf("PROPFIND 1", "COPY"), methods)
    }

    @Test fun `conflict after a negative check is unclear and needs the user`() {
        val (added, _) = run(recovered = true, members = emptyList(), copy = 409)
        assertFalse(added)
    }

    /** Returns the step result and the DAV methods it sent, PROPFIND with its depth. */
    private fun run(recovered: Boolean, members: List<Long>, copy: Int): Pair<Boolean, List<String>> = runBlocking {
        MemoriesAlbumWriterTest.Fixture { request ->
            when (request.method) {
                "COPY" -> copy to ""
                "PROPFIND" -> 207 to found(*members.map { it to "Photos/$it.jpg" }.toTypedArray())
                else -> 500 to ""
            }
        }.use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val catalog = AlbumCatalogRepository(
                    SessionStore(FixedCredentials(fixture.credentials)),
                    object : RemoteAlbumSource {
                        override suspend fun load(credentials: AccountCredentials) = RemoteAlbums(emptyList())
                    },
                    object : LocalAlbumSource {
                        override suspend fun load() = emptyList<AlbumSummary>()
                        override fun changes() = emptyFlow<Unit>()
                    },
                    MutableStateFlow(null),
                    scope,
                )
                val selection = UploadSelection(MediaAssetRef.LocalContent("content://media/a", 1, 1), "a.jpg", "image/jpeg")
                val job = UploadJob("job", "owner", selection, UploadAlbum("anna", "Лето"))
                val file = UploadedFile(selection, DavFilePath.of("anna", "/Photos/a.jpg"), created = true, fileId = FILE_ID)
                val added = AlbumUploadFollowUp(fixture.writer, catalog).run(fixture.credentials, job, file, recovered)
                added to fixture.requests.map { if (it.method == "PROPFIND") "PROPFIND ${it.headers["depth"]}" else it.method }
            } finally {
                scope.cancel()
            }
        }
    }

    private class FixedCredentials(private val credentials: AccountCredentials) : CredentialsStore {
        override fun load() = credentials
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    }

    private companion object {
        const val FILE_ID = 7L
    }
}

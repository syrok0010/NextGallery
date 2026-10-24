package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.upload.UploadSelection
import com.syrok0010.nextgallery.feature.upload.UploadedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AlbumUploadFollowUpTest {
    @Test fun `summary counts files already in album as added and missed files as not added`() {
        assertEquals(
            uiText(R.string.album_upload_summary_failed, "Лето", 2, 2),
            albumUploadSummary(
                "Лето",
                listOf(AlbumCopyOutcome.Added, AlbumCopyOutcome.AlreadyPresent, AlbumCopyOutcome.Failed),
                missed = 1,
            ),
        )
        assertEquals(
            uiText(R.string.album_upload_summary, "Лето", 1),
            albumUploadSummary("Лето", listOf(AlbumCopyOutcome.Added), missed = 0),
        )
        assertNull(albumUploadSummary("Лето", emptyList(), missed = 0))
    }

    @Test fun `uploaded files join the album and the catalog refreshes once the batch ends`() = runBlocking {
        MemoriesAlbumWriterTest.Fixture { request ->
            if (request.decodedTarget.endsWith("b.jpg")) 500 to "" else 201 to ""
        }.use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                var refreshed = 0
                val sessions = SessionStore(FixedCredentials(fixture.credentials))
                val catalog = AlbumCatalogRepository(
                    sessions,
                    object : RemoteAlbumSource {
                        override suspend fun load(credentials: AccountCredentials): RemoteAlbums {
                            refreshed++
                            return RemoteAlbums(emptyList())
                        }
                    },
                    object : LocalAlbumSource {
                        override suspend fun load() = emptyList<AlbumSummary>()
                        override fun changes() = emptyFlow<Unit>()
                    },
                    MutableStateFlow<LocalMediaPermissionMode?>(null),
                    scope,
                )
                val followUp = AlbumUploadFollowUp(fixture.writer, catalog, AlbumAddress("anna", "Лето"))
                followUp.uploaded(fixture.credentials, uploaded(fixture, "a.jpg"))
                followUp.uploaded(fixture.credentials, uploaded(fixture, "b.jpg"))
                val before = refreshed

                assertEquals(
                    listOf(uiText(R.string.album_upload_summary_failed, "Лето", 1, 2)),
                    followUp.finished(listOf(selection("c.jpg"))),
                )
                assertEquals(
                    listOf("/remote.php/dav/files/anna/Photos/a.jpg", "/remote.php/dav/files/anna/Photos/b.jpg"),
                    fixture.requests.filter { it.method == "COPY" }.map { it.decodedTarget },
                )
                withTimeout(5_000) { while (refreshed == before) delay(10) }
            } finally {
                scope.cancel()
            }
        }
    }

    private fun selection(name: String) = UploadSelection("content://media/$name", name, "image/jpeg")

    private fun uploaded(fixture: MemoriesAlbumWriterTest.Fixture, name: String) =
        UploadedFile(selection(name), fileUrl(fixture.origin, "anna", "/Photos/$name"), created = true)

    private class FixedCredentials(private val credentials: AccountCredentials) : CredentialsStore {
        override fun load() = credentials
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    }
}

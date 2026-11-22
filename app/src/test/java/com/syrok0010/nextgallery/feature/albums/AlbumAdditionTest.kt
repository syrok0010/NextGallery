package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.core.network.Recorded
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.ui.pluralText
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.upload.EnqueueResult
import com.syrok0010.nextgallery.feature.upload.UploadAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AlbumAdditionTest {
    @Test fun `request keeps server copies and uploads local-only objects`() {
        val request = AlbumAdditionRequest.of(
            listOf(
                item("remote", MediaAssetRef.MemoriesFile(10)),
                item("merged", MediaAssetRef.LocalFirst(local("merged"), MediaAssetRef.MemoriesFile(11))),
                item("local", local("local")),
            ),
        )
        assertEquals(listOf(10L, 11L), request.fileIds)
        assertEquals(listOf(MediaId("local")), request.uploads)
    }

    @Test fun `only albums owned by current user are writable`() {
        val albums = listOf(
            album("own", "anna/Лето"),
            album("shared", "boris/Лето"),
            album("folder", null),
        )
        assertEquals(listOf("own"), writableAlbums(albums, "anna").map { it.id })
    }

    @Test fun `new album name rejects blanks, slashes and existing names`() {
        assertEquals(AlbumNameProblem.Blank, albumNameProblem("  ", emptySet()))
        assertEquals(AlbumNameProblem.Slash, albumNameProblem("a/b", emptySet()))
        assertEquals(AlbumNameProblem.Exists, albumNameProblem(" Лето ", setOf("Лето")))
        assertNull(albumNameProblem("Лето & море", setOf("Лето")))
    }

    @Test fun `result message mentions only non-zero counters`() {
        assertEquals(
            listOf(
                uiText(R.string.album_add_done, "Лето", 3),
                pluralText(R.plurals.album_add_done_failed, 1, 1),
                pluralText(R.plurals.album_add_done_uploading, 2, 2),
            ),
            albumAdditionMessage(
                AlbumAdditionState.Done("Лето", AlbumAddResult(3, 0, 1), localUploads = 2),
            ),
        )
        assertEquals(
            listOf(pluralText(R.plurals.album_add_uploading_only, 2, "Лето", 2)),
            albumAdditionMessage(
                AlbumAdditionState.Done("Лето", AlbumAddResult(0, 0, 0), localUploads = 2),
            ),
        )
        assertNull(albumAdditionMessage(AlbumAdditionState.Idle))
    }

    @Test fun `additions create album, add files and refresh catalog`() = runBlocking {
        NextcloudFixture { request ->
            when {
                request.method == "MKCOL" -> 201 to ""
                request.method == "SEARCH" -> 207 to found(request.searchedFileId to "Photos/a.jpg")
                request.method == "COPY" -> 201 to ""
                else -> 404 to ""
            }
        }.use { fixture ->
            val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
            try {
                val sessions = SessionStore(FixedCredentials(fixture.credentials))
                var refreshed = 0
                val submittedAlbum = MutableStateFlow<UploadAlbum?>(null)
                val additions = fixture.albums(scope, sessions = sessions,
                    catalog = { refreshed++; RemoteAlbums(emptyList()) },
                    enqueue = { ids, album ->
                        submittedAlbum.value = album
                        EnqueueResult.Accepted(ids.size)
                    },
                )
                val before = refreshed
                var mkcolBeforeSubmit = false
                // Unconfined: runs inside submit, before the addition goes on.
                val submitted = scope.launch(Dispatchers.Unconfined) {
                    submittedAlbum.first { it != null }
                    mkcolBeforeSubmit = fixture.requests.any { it.method == "MKCOL" }
                }
                val accepted = additions.add(
                    AlbumTarget.New("Поход"),
                    AlbumAdditionRequest(listOf(1L), listOf(MediaId("local"))),
                )
                assertTrue(accepted)
                val done = withTimeout(5_000) {
                    additions.operations.first { it.singleOrNull()?.running == false }.single()
                }
                assertEquals(uiText(R.string.album_add_done, "Поход", 1), done.title)
                assertEquals(listOf(pluralText(R.plurals.album_add_done_uploading, 1, 1)), done.details)
                submitted.join()
                assertTrue("uploads must start after the album exists", mkcolBeforeSubmit)
                assertEquals(UploadAlbum("anna", "Поход"), submittedAlbum.value)
                assertTrue(fixture.requests.any { it.method == "MKCOL" && it.decodedTarget.endsWith("/photos/anna/albums/Поход") })
                withTimeout(5_000) { while (refreshed == before) kotlinx.coroutines.delay(10) }
                additions.consumeAll()
                withTimeout(5_000) { additions.operations.first { it.isEmpty() } }
                Unit
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun `second addition is rejected while the first is running`() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        NextcloudFixture { request ->
            when {
                request.method == "SEARCH" -> 207 to found(request.searchedFileId to "Photos/a.jpg")
                request.method == "COPY" -> {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    201 to ""
                }
                else -> 404 to ""
            }
        }.use { fixture ->
            val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
            try {
                val sessions = SessionStore(FixedCredentials(fixture.credentials))
                val additions = fixture.albums(scope, sessions = sessions)
                val album = AlbumTarget.Existing(AlbumLocation.Remote("anna/Лето"), "Лето")
                val request = AlbumAdditionRequest(listOf(1L))
                assertTrue(additions.add(album, request))
                assertFalse(additions.add(album, request))
                release.countDown()
                withTimeout(5_000) { additions.operations.first { it.singleOrNull()?.running == false } }
                additions.consumeAll()
                withTimeout(5_000) {
                    while (!additions.add(album, request)) kotlinx.coroutines.delay(10)
                }
            } finally {
                release.countDown()
                scope.cancel()
            }
        }
    }

    @Test fun `album of another user is not written and its photos are not uploaded`() = runBlocking {
        val outcome = failedAddition(AlbumTarget.Existing(AlbumLocation.Remote("bob/Лето"), "Лето")) { 201 to "" }

        assertEquals(uiText(R.string.album_add_failed_not_writable, "Лето"), outcome.message)
        assertEquals(emptyList<String>(), outcome.requests)
        assertEquals(0, outcome.enqueued)
    }

    @Test fun `taken album name fails as such and uploads nothing`() = runBlocking {
        val outcome = failedAddition(AlbumTarget.New("Лето")) { 405 to "" }

        assertEquals(uiText(R.string.album_add_failed_exists, "Лето"), outcome.message)
        assertEquals(listOf("MKCOL"), outcome.requests)
        assertEquals(0, outcome.enqueued)
    }

    @Test fun `server failure is reported as a network problem and uploads nothing`() = runBlocking {
        val outcome = failedAddition(AlbumTarget.New("Лето")) { 503 to "" }

        assertEquals(uiText(R.string.album_add_failed_network, "Лето"), outcome.message)
        assertEquals(0, outcome.enqueued)
    }

    @Test fun `refused upload queue stops the addition before any copy`() = runBlocking {
        val outcome = failedAddition(
            AlbumTarget.New("Лето"),
            enqueue = EnqueueResult.Rejected,
        ) { if (it.method == "MKCOL") 201 to "" else 500 to "" }

        assertEquals(uiText(R.string.uploads_enqueue_rejected, "Лето"), outcome.message)
        assertEquals(listOf("MKCOL"), outcome.requests)
        assertEquals(1, outcome.enqueued)
    }

    private class FailedAddition(
        val message: com.syrok0010.nextgallery.core.ui.UiText,
        /** Methods the server saw, in order. */
        val requests: List<String>,
        val enqueued: Int,
    )

    /**
     * Adds a server copy and a phone-only photo to [target] as anna and waits for the failure.
     */
    private suspend fun failedAddition(
        target: AlbumTarget,
        enqueue: EnqueueResult = EnqueueResult.Accepted(1),
        respond: (Recorded) -> Pair<Int, String>,
    ): FailedAddition =
        NextcloudFixture(respond).use { fixture ->
            val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
            try {
                val sessions = SessionStore(FixedCredentials(fixture.credentials))
                val enqueued = java.util.concurrent.atomic.AtomicInteger()
                val additions = fixture.albums(scope, sessions = sessions, enqueue = { _, _ ->
                    enqueued.incrementAndGet()
                    enqueue
                })
                fixture.requests.clear()

                assertTrue(additions.add(target, AlbumAdditionRequest(listOf(1L), listOf(MediaId("phone-only")))))

                val failed = withTimeout(5_000) {
                    additions.operations.first { it.singleOrNull()?.failed == true }.single()
                }
                FailedAddition(
                    failed.title,
                    fixture.requests.map { it.method },
                    enqueued.get(),
                )
            } finally {
                scope.cancel()
            }
        }

    private class FixedCredentials(private val credentials: AccountCredentials) : CredentialsStore {
        override fun load() = credentials
        override fun save(credentials: AccountCredentials) = Unit
    }

    private fun local(id: String) = MediaAssetRef.LocalContent("content://media/$id", 1, 1)

    private fun item(id: String, asset: MediaAssetRef) =
        MediaItem(
            mediaId = MediaId(id),
            dayId = 1,
            displayName = "$id.jpg",
            mimeType = "image/jpeg",
            width = null,
            height = null,
            etag = null,
            livePhotoId = null,
            auid = null,
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = 1,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = asset,
        )

    private fun album(id: String, clusterId: String?) =
        AlbumSummary(
            id = id,
            name = id,
            origin = if (clusterId == null) AlbumOrigin.Phone else AlbumOrigin.Nextcloud,
            count = 1,
            detail = "",
            location = clusterId?.let(AlbumLocation::Remote),
        )
}

package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.albums.MemoriesAlbumWriterTest.Fixture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDeletionTest {
    @Test fun `plan splits copies and scope only narrows objects with both copies`() {
        val preparation = checkNotNull(
            DeletionPreparation.of(
                listOf(
                    item("phone", local(1)),
                    item("cloud", MediaAssetRef.MemoriesFile(10)),
                    item("both", MediaAssetRef.LocalFirst(local(2), MediaAssetRef.MemoriesFile(20))),
                    item("video", local(3), isVideo = true),
                ),
            ),
        )
        assertEquals(DeletionPlan(phoneOnly = 2, cloudOnly = 1, merged = 1), preparation.plan)
        assertEquals(
            DeletionTargets(
                contentUris = listOf(
                    "content://media/external/images/media/1",
                    "content://media/external/video/media/3",
                    "content://media/external/images/media/2",
                ),
                fileIds = listOf(10, 20),
            ),
            preparation.confirm(DeletionScope.Everywhere),
        )
        assertEquals(
            DeletionTargets(
                contentUris = listOf(
                    "content://media/external/images/media/1",
                    "content://media/external/video/media/3",
                    "content://media/external/images/media/2",
                ),
                fileIds = listOf(10),
            ),
            preparation.confirm(DeletionScope.PhoneOnly),
        )
        assertEquals(
            DeletionTargets(
                contentUris = listOf(
                    "content://media/external/images/media/1",
                    "content://media/external/video/media/3",
                ),
                fileIds = listOf(10, 20),
            ),
            preparation.confirm(DeletionScope.CloudOnly),
        )
    }

    @Test fun `files URIs are rewritten to typed collections, other URIs are kept`() {
        assertEquals(
            "content://media/external_primary/video/media/7",
            mediaStoreItemUri("content://media/external_primary/file/7", isVideo = true),
        )
        assertEquals(
            "content://media/external/images/media/7",
            mediaStoreItemUri("content://media/external/images/media/7", isVideo = false),
        )
        assertEquals(
            "content://other/file/7",
            mediaStoreItemUri("content://other/file/7", isVideo = false),
        )
    }

    @Test fun `repository skips objects gone from the library and keeps the prepared copies stable`() {
        Fixture(::server).use { fixture ->
            val index = MediaLibraryIndex()
            index.publish(listOf(item("both", MediaAssetRef.LocalFirst(local(2), MediaAssetRef.MemoriesFile(20)))))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val repository = MediaDeletionRepository(
                    SessionStore(FixedCredentials(fixture.credentials)),
                    index,
                    MemoriesFileDeleter(fixture.writerTransport, fixture.files),
                    { it.size },
                    scope,
                ) {}
                val preparation = repository.prepare(listOf(MediaId("both"), MediaId("gone")))
                assertEquals(DeletionPlan(phoneOnly = 0, cloudOnly = 0, merged = 1), preparation?.plan)

                index.clear()

                assertEquals(
                    DeletionTargets(
                        contentUris = listOf("content://media/external/images/media/2"),
                        fileIds = listOf(20),
                    ),
                    preparation?.confirm(DeletionScope.Everywhere),
                )
                assertEquals(null, repository.prepare(listOf(MediaId("both"))))
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun `cloud delete counts 404 as deleted and isolates per-file failures`() {
        assertEquals(true, cloudDeleteSucceeded(204))
        assertEquals(true, cloudDeleteSucceeded(404))
        assertEquals(false, cloudDeleteSucceeded(412))
        assertEquals(false, cloudDeleteSucceeded(423))
        assertEquals(false, cloudDeleteSucceeded(500))
    }

    @Test fun `deleter sends DAV DELETE to the uid path of each server file`() = runBlocking {
        Fixture(::server).use { fixture ->
            val deleter = MemoriesFileDeleter(fixture.writerTransport, fixture.files)
            assertEquals(
                CloudDeleteResult(deleted = 2, failed = 2),
                deleter.delete(fixture.credentials, listOf(1, 2, 3, 4, 1)),
            )
            val deletes = fixture.requests.filter { it.method == "DELETE" }.map { it.decodedTarget }.sorted()
            assertEquals(
                listOf(
                    "/remote.php/dav/files/anna/Photos/Поход 1.jpg",
                    "/remote.php/dav/files/anna/Photos/Поход 2.jpg",
                    "/remote.php/dav/files/anna/Photos/Поход 3.jpg",
                ),
                deletes,
            )
            val pinned = fixture.requests.single { it.method == "DELETE" && it.decodedTarget.endsWith("Поход 1.jpg") }
            assertEquals("\"etag-1\"", pinned.headers["if-match"])
        }
    }

    @Test fun `a file replaced at the same path is not deleted`() = runBlocking {
        Fixture { request ->
            when {
                request.target.startsWith("/apps/memories/api/image/info/") ->
                    200 to """{"filename":"/Photos/a.jpg","basename":"a.jpg","etag":"old"}"""
                request.method == "DELETE" && request.headers["if-match"] == "\"old\"" -> 412 to ""
                else -> 204 to ""
            }
        }.use { fixture ->
            assertEquals(
                CloudDeleteResult(deleted = 0, failed = 1),
                MemoriesFileDeleter(fixture.writerTransport, fixture.files).delete(fixture.credentials, listOf(1)),
            )
        }
    }

    @Test fun `repository reports phone and cloud results and refreshes the library`() = runBlocking {
        Fixture(::server).use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val refreshed = AtomicInteger()
                val sessions = SessionStore(FixedCredentials(fixture.credentials))
                val repository = MediaDeletionRepository(
                    sessions,
                    MediaLibraryIndex(),
                    MemoriesFileDeleter(fixture.writerTransport, fixture.files),
                    { it.size - 1 },
                    scope,
                ) { refreshed.incrementAndGet() }
                val phone = listOf("content://media/external/images/media/8", "content://media/external/images/media/9")
                repository.delete(
                    DeletionTargets(phone, listOf(1, 3)),
                )
                // Queued behind the first; its result adds to the one not shown yet.
                repository.delete(
                    DeletionTargets(emptyList(), listOf(2)),
                )
                withTimeout(5_000) { while (refreshed.get() < 2) kotlinx.coroutines.delay(10) }
                assertEquals(
                    DeletionResult(
                        phoneTrashed = 1,
                        cloud = CloudDeleteResult(deleted = 2, failed = 1),
                        phoneFailed = 1,
                    ),
                    repository.result.value,
                )
                assertEquals("progress ends with the queue", null, repository.progress.value)
                repository.consume()
                assertEquals(null, repository.result.value)
                repository.delete(
                    DeletionTargets(emptyList(), listOf(1)),
                )
                assertEquals(
                    DeletionResult(phoneTrashed = 0, cloud = CloudDeleteResult(deleted = 1, failed = 0)),
                    withTimeout(5_000) { repository.result.filterNotNull().first() },
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun `progress marks queued cloud copies until the queue is done`() = runBlocking {
        val release = CountDownLatch(1)
        Fixture { request ->
            release.await()
            server(request)
        }.use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val repository = MediaDeletionRepository(
                    SessionStore(FixedCredentials(fixture.credentials)),
                    MediaLibraryIndex(),
                    MemoriesFileDeleter(fixture.writerTransport, fixture.files),
                    { it.size },
                    scope,
                ) {}
                repository.delete(DeletionTargets(emptyList(), listOf(1, 3)))
                repository.delete(DeletionTargets(listOf("content://media/external/images/media/8"), listOf(2)))
                assertEquals(
                    DeletionProgress(fileIds = setOf(1, 2, 3), total = 3, processed = 0),
                    repository.progress.value,
                )
                release.countDown()
                withTimeout(5_000) { repository.progress.first { it == null } }
                assertEquals(
                    DeletionResult(phoneTrashed = 1, cloud = CloudDeleteResult(deleted = 2, failed = 1)),
                    repository.result.value,
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun `without a session every cloud copy counts as failed`() = runBlocking {
        Fixture(::server).use { fixture ->
            // The session reset runs inside the constructor, not after the deletion.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val repository = MediaDeletionRepository(
                    SessionStore(FixedCredentials(null)),
                    MediaLibraryIndex(),
                    MemoriesFileDeleter(fixture.writerTransport, fixture.files),
                    { it.size },
                    scope,
                ) {}
                repository.delete(
                    DeletionTargets(emptyList(), listOf(1, 2)),
                )
                assertEquals(
                    DeletionResult(phoneTrashed = 0, cloud = CloudDeleteResult(deleted = 0, failed = 2)),
                    withTimeout(5_000) { repository.result.filterNotNull().first() },
                )
                assertEquals(emptyList<String>(), fixture.requests.map { it.method })
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun `message stays silent for zero counters`() {
        assertEquals(
            listOf("delete_done_phone_failed", "delete_done_cloud", "delete_done_failed"),
            mediaDeletionMessage(DeletionResult(0, CloudDeleteResult(deleted = 2, failed = 1), phoneFailed = 1))
                .map { resourceName(it) },
        )
        assertEquals(emptyList<Any>(), mediaDeletionMessage(DeletionResult(0, CloudDeleteResult(0, 0))))
    }

    private fun resourceName(text: com.syrok0010.nextgallery.core.ui.UiText): String {
        val id = (text as com.syrok0010.nextgallery.core.ui.UiText.Resource).id
        return com.syrok0010.nextgallery.R.string::class.java.fields
            .single { it.getInt(null) == id }
            .name
    }

    private fun server(request: com.syrok0010.nextgallery.feature.albums.MemoriesAlbumWriterTest.Recorded) =
        when {
            request.target.startsWith("/apps/memories/api/image/info/") -> {
                val id = request.target.substringAfterLast('/').toLong()
                if (id == 4L) {
                    500 to ""
                } else {
                    200 to """{"filename":"/Photos/Поход $id.jpg","basename":"Поход $id.jpg","etag":"etag-$id"}"""
                }
            }
            request.method == "DELETE" && request.decodedTarget.endsWith("Поход 2.jpg") -> 404 to ""
            request.method == "DELETE" && request.decodedTarget.endsWith("Поход 3.jpg") -> 423 to ""
            request.method == "DELETE" -> 204 to ""
            else -> 404 to ""
        }

    private class FixedCredentials(private val credentials: AccountCredentials?) : CredentialsStore {
        override fun load() = credentials
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    }

    private fun local(id: Long) = MediaAssetRef.LocalContent("content://media/external/file/$id", 1)

    private fun item(id: String, asset: MediaAssetRef, isVideo: Boolean = false) =
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
            isVideo = isVideo,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = asset,
        )
}

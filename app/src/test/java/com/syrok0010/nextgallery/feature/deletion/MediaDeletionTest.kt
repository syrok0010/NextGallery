package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.core.network.Recorded
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.timeline.remote.InMemoryMediaIdentityRegistry
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesFiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            targets(
                contentUris = listOf(
                    "content://media/external/images/media/1",
                    "content://media/external/video/media/3",
                    "content://media/external/images/media/2",
                ),
                fileIds = listOf(10, 20),
            ),
            preparation.confirm(DeletionScope.Everywhere, cloudAllowed = true),
        )
        assertEquals(
            targets(
                contentUris = listOf(
                    "content://media/external/images/media/1",
                    "content://media/external/video/media/3",
                    "content://media/external/images/media/2",
                ),
                fileIds = listOf(10),
            ),
            preparation.confirm(DeletionScope.PhoneOnly, cloudAllowed = true),
        )
        assertEquals(
            targets(
                contentUris = listOf(
                    "content://media/external/images/media/1",
                    "content://media/external/video/media/3",
                ),
                fileIds = listOf(10, 20),
            ),
            preparation.confirm(DeletionScope.CloudOnly, cloudAllowed = true),
        )
        // Without an answer about the server trash the cloud stays untouched.
        assertEquals(
            targets(
                contentUris = listOf(
                    "content://media/external/images/media/1",
                    "content://media/external/video/media/3",
                    "content://media/external/images/media/2",
                ),
                fileIds = emptyList(),
            ),
            preparation.confirm(DeletionScope.Everywhere, cloudAllowed = false),
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
        NextcloudFixture(::server).use { fixture ->
            val index = MediaLibraryIndex()
            index.publish(listOf(item("both", MediaAssetRef.LocalFirst(local(2), MediaAssetRef.MemoriesFile(20)))))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val repository = MediaDeletionRepository(
                    SessionStore(FixedCredentials(fixture.credentials)),
                    index,
                    MemoriesCloudTrash(fixture.dav, MemoriesFiles(fixture.memories)),
                    InMemoryMediaIdentityRegistry(),
                    { it },
                    scope,
                ) {}
                val preparation = repository.prepare(listOf(MediaId("both"), MediaId("gone")))
                assertEquals(DeletionPlan(phoneOnly = 0, cloudOnly = 0, merged = 1), preparation?.plan)

                index.clear()

                assertEquals(
                    targets(
                        contentUris = listOf("content://media/external/images/media/2"),
                        fileIds = listOf(20),
                    ),
                    preparation?.confirm(DeletionScope.Everywhere, cloudAllowed = true),
                )
                assertEquals(null, repository.prepare(listOf(MediaId("both"))))
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun `deleter sends DAV DELETE to the uid path of each server file`() = runBlocking {
        NextcloudFixture(::server).use { fixture ->
            val trash = MemoriesCloudTrash(fixture.dav, MemoriesFiles(fixture.memories))
            assertEquals(
                listOf(true, true, false, false),
                listOf(1L, 2L, 3L, 4L).map { trash.trash(fixture.credentials, it) },
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
        NextcloudFixture { request ->
            when {
                request.target.startsWith("/apps/memories/api/image/info/") ->
                    200 to """{"filename":"/Photos/a.jpg","basename":"a.jpg","etag":"old"}"""
                request.method == "DELETE" && request.headers["if-match"] == "\"old\"" -> 412 to ""
                else -> 204 to ""
            }
        }.use { fixture ->
            assertEquals(
                false,
                MemoriesCloudTrash(fixture.dav, MemoriesFiles(fixture.memories)).trash(fixture.credentials, 1),
            )
        }
    }

    @Test fun `repository reports phone and cloud results and refreshes the library`() = runBlocking {
        NextcloudFixture(::server).use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val refreshed = AtomicInteger()
                val sessions = SessionStore(FixedCredentials(fixture.credentials))
                val repository = MediaDeletionRepository(
                    sessions,
                    MediaLibraryIndex(),
                    CloudTrash { _, fileId -> fileId != 3L },
                    InMemoryMediaIdentityRegistry(),
                    { it.drop(1) },
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
                    repository.state.value.unseen,
                )
                assertEquals("progress ends with the queue", null, repository.state.value.running)
                repository.consume()
                assertEquals(null, repository.state.value.unseen)
                repository.delete(
                    DeletionTargets(emptyList(), listOf(1)),
                )
                assertEquals(
                    DeletionResult(phoneTrashed = 0, cloud = CloudDeleteResult(deleted = 1, failed = 0)),
                    withTimeout(5_000) { repository.state.map { it.unseen }.filterNotNull().first() },
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun `a deleted copy is no longer linked to the other copy`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val identities = InMemoryMediaIdentityRegistry()
            val cloudDeleted = phoneCopyLinkedTo(identities, 1)
            val cloudFailed = phoneCopyLinkedTo(identities, 3)
            phoneCopyLinkedTo(identities, 5)
            phoneCopyLinkedTo(identities, 7)
            val refreshed = AtomicInteger()
            val repository = MediaDeletionRepository(
                SessionStore(
                    FixedCredentials(AccountCredentials("https://cloud.example", "anna", "password", "anna")),
                ),
                MediaLibraryIndex(),
                CloudTrash { _, fileId -> fileId != 3L },
                identities,
                // Phone copy 7 did not move to the trash.
                { uris -> uris.filterNot { it.endsWith("/7") } },
                scope,
            ) { refreshed.incrementAndGet() }
            repository.delete(DeletionTargets(emptyList(), listOf(1, 3)))
            repository.delete(
                targets(
                    contentUris = listOf("content://media/external/images/media/5", "content://media/external/images/media/7"),
                    fileIds = emptyList(),
                ),
            )
            withTimeout(5_000) { while (refreshed.get() < 2) kotlinx.coroutines.delay(10) }

            // The phone file uploaded again links to the new copy; a copy still on the server stays.
            assertEquals(true, identities.link(cloudDeleted, memories(11)))
            assertEquals(false, identities.link(cloudFailed, memories(13)))
            // The same for a cloud copy whose phone copy went to the trash.
            assertEquals(true, identities.link(memories(5), phone(15)))
            assertEquals(false, identities.link(memories(7), phone(17)))
        } finally {
            scope.cancel()
        }
    }

    private suspend fun phoneCopyLinkedTo(
        identities: InMemoryMediaIdentityRegistry,
        id: Long,
    ): MediaSourceIdentity {
        val phone = phone(id)
        identities.resolve(listOf(MediaIdentityCandidate(source = phone, aliases = emptySet())))
        assertEquals(true, identities.link(phone, memories(id)))
        return phone
    }

    private fun phone(id: Long) = MediaSourceIdentity(MediaSourceKind.Local, "content://media/external/file/$id")

    private fun memories(fileId: Long) = MediaSourceIdentity(MediaSourceKind.Memories, fileId.toString())

    /** Phone copies are indexed by their `file` URI and trashed by the typed one. */
    private fun targets(contentUris: List<String>, fileIds: List<Long>) =
        DeletionTargets(
            contentUris = contentUris,
            fileIds = fileIds,
            phoneSources = contentUris.associateWith { "content://media/external/file/${it.substringAfterLast('/')}" },
        )

    @Test fun `progress marks queued cloud copies until the queue is done`() = runBlocking {
        val release = CountDownLatch(1)
        NextcloudFixture { request ->
            release.await()
            server(request)
        }.use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val repository = MediaDeletionRepository(
                    SessionStore(FixedCredentials(fixture.credentials)),
                    MediaLibraryIndex(),
                    CloudTrash { _, fileId ->
                        release.await()
                        fileId != 3L
                    },
                    InMemoryMediaIdentityRegistry(),
                    { it },
                    scope,
                ) {}
                repository.delete(DeletionTargets(emptyList(), listOf(1, 3)))
                repository.delete(DeletionTargets(listOf("content://media/external/images/media/8"), listOf(2)))
                assertEquals(
                    DeletionProgress(queued = listOf(1, 3, 2), total = 3, processed = 0, requests = 2),
                    repository.state.value.running,
                )
                release.countDown()
                withTimeout(5_000) { repository.state.first { it.running == null } }
                assertEquals(
                    DeletionResult(phoneTrashed = 1, cloud = CloudDeleteResult(deleted = 2, failed = 1)),
                    repository.state.value.unseen,
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun `a file named again by a queued deletion stays marked until that one ends too`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gates = listOf(CountDownLatch(1), CountDownLatch(1))
        try {
            val calls = AtomicInteger()
            val finished = AtomicInteger()
            val repository = MediaDeletionRepository(
                SessionStore(FixedCredentials(AccountCredentials("https://cloud.example", "anna", "password", "anna"))),
                MediaLibraryIndex(),
                // Each request waits for its own gate, so the second is queued while the first runs.
                CloudTrash { _, _ ->
                    gates[calls.getAndIncrement()].await()
                    true
                },
                InMemoryMediaIdentityRegistry(),
                { it },
                scope,
            ) { finished.incrementAndGet() }

            repository.delete(DeletionTargets(emptyList(), listOf(1)))
            repository.delete(DeletionTargets(emptyList(), listOf(1)))
            gates[0].countDown()
            withTimeout(5_000) { while (finished.get() < 1) kotlinx.coroutines.delay(10) }

            assertEquals(setOf(1L), repository.state.value.running?.fileIds)
            gates[1].countDown()
            // The result is published before the callback, so the queue is empty by now.
            withTimeout(5_000) { while (finished.get() < 2) kotlinx.coroutines.delay(10) }
            assertEquals(null, repository.state.value.running)
        } finally {
            gates.forEach { it.countDown() }
            scope.cancel()
        }
    }

    @Test fun `a phone-only deletion counts as running until the trash is checked`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val checked = CountDownLatch(1)
        try {
            val finished = AtomicInteger()
            val repository = MediaDeletionRepository(
                SessionStore(FixedCredentials(AccountCredentials("https://cloud.example", "anna", "password", "anna"))),
                MediaLibraryIndex(),
                CloudTrash { _, _ -> error("no cloud copy to delete") },
                InMemoryMediaIdentityRegistry(),
                { uris ->
                    checked.await()
                    uris
                },
                scope,
            ) { finished.incrementAndGet() }

            repository.delete(targets(listOf("content://media/external/images/media/5"), emptyList()))

            // A running operation keeps logout, which ends the process, disabled.
            val shown = withTimeout(5_000) { repository.operations.first { it.isNotEmpty() } }.single()
            assertTrue(shown.running)
            assertEquals("delete_progress_phone", resourceName(shown.title))
            checked.countDown()
            withTimeout(5_000) { while (finished.get() < 1) kotlinx.coroutines.delay(10) }
            assertEquals(null, repository.state.value.running)
        } finally {
            checked.countDown()
            scope.cancel()
        }
    }

    @Test fun `an unreadable phone trash check counts the confirmed copies as trashed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = MediaDeletionRepository(
                SessionStore(FixedCredentials(AccountCredentials("https://cloud.example", "anna", "password", "anna"))),
                MediaLibraryIndex(),
                CloudTrash { _, _ -> true },
                InMemoryMediaIdentityRegistry(),
                { error("MediaStore did not answer") },
                scope,
            ) {}

            repository.delete(
                targets(listOf("content://media/external/images/media/5", "content://media/external/images/media/7"), listOf(1)),
            )

            // The user confirmed the system dialog, so the phone half is not reported as failed.
            assertEquals(
                DeletionResult(phoneTrashed = 2, cloud = CloudDeleteResult(deleted = 1, failed = 0), phoneFailed = 0),
                withTimeout(5_000) { repository.state.map { it.unseen }.filterNotNull().first() },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test fun `without a session every cloud copy counts as failed`() = runBlocking {
        NextcloudFixture(::server).use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val repository = MediaDeletionRepository(
                    SessionStore(FixedCredentials(null)),
                    MediaLibraryIndex(),
                    CloudTrash { _, _ -> error("no cloud request expected") },
                    InMemoryMediaIdentityRegistry(),
                    { it },
                    scope,
                ) {}
                repository.delete(
                    DeletionTargets(emptyList(), listOf(1, 2)),
                )
                assertEquals(
                    DeletionResult(phoneTrashed = 0, cloud = CloudDeleteResult(deleted = 0, failed = 2)),
                    withTimeout(5_000) { repository.state.map { it.unseen }.filterNotNull().first() },
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

    private fun server(request: Recorded) =
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
    }

    private fun local(id: Long) = MediaAssetRef.LocalContent("content://media/external/file/$id", 1, 1)

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

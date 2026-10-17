package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.albums.MemoriesAlbumWriterTest.Fixture
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDeletionTest {
    @Test fun `plan splits copies and scope only narrows objects with both copies`() {
        val plan = DeletionPlan.of(
            listOf(
                item("phone", local(1)),
                item("cloud", MediaAssetRef.MemoriesFile(10)),
                item("both", MediaAssetRef.LocalFirst(local(2), MediaAssetRef.MemoriesFile(20))),
                item("video", local(3), isVideo = true),
            ),
        )
        assertEquals(4, plan.total)
        assertEquals(
            DeletionTargets(
                contentUris = listOf(
                    "content://media/external/images/media/1",
                    "content://media/external/video/media/3",
                    "content://media/external/images/media/2",
                ),
                fileIds = listOf(10, 20),
            ),
            plan.targets(DeletionScope.Everywhere),
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
            plan.targets(DeletionScope.PhoneOnly),
        )
        assertEquals(
            DeletionTargets(
                contentUris = listOf(
                    "content://media/external/images/media/1",
                    "content://media/external/video/media/3",
                ),
                fileIds = listOf(10, 20),
            ),
            plan.targets(DeletionScope.CloudOnly),
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

    @Test fun `cloud delete counts 404 as deleted and isolates per-file failures`() {
        assertEquals(true, cloudDeleteSucceeded(204))
        assertEquals(true, cloudDeleteSucceeded(404))
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
                    MemoriesFileDeleter(fixture.writerTransport, fixture.files),
                    scope,
                ) { refreshed.incrementAndGet() }
                repository.delete(DeletionTargets(listOf("content://media/external/images/media/9"), listOf(1, 3)))
                val done = withTimeout(5_000) {
                    repository.state.first { it is MediaDeletionState.Done }
                }
                assertEquals(
                    MediaDeletionState.Done(phoneTrashed = 1, cloud = CloudDeleteResult(deleted = 1, failed = 1)),
                    done,
                )
                assertEquals(1, refreshed.get())
                repository.consume()
                assertEquals(MediaDeletionState.Idle, repository.state.value)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun `without a session every cloud copy counts as failed`() = runBlocking {
        Fixture(::server).use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val repository = MediaDeletionRepository(
                    SessionStore(FixedCredentials(null)),
                    MemoriesFileDeleter(fixture.writerTransport, fixture.files),
                    scope,
                ) {}
                repository.delete(DeletionTargets(emptyList(), listOf(1, 2)))
                assertEquals(
                    MediaDeletionState.Done(phoneTrashed = 0, cloud = CloudDeleteResult(deleted = 0, failed = 2)),
                    withTimeout(5_000) { repository.state.first { it is MediaDeletionState.Done } },
                )
                assertEquals(emptyList<String>(), fixture.requests.map { it.method })
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun `message stays silent for zero counters`() {
        assertEquals(
            listOf("delete_done_cloud", "delete_done_failed"),
            mediaDeletionMessage(MediaDeletionState.Done(0, CloudDeleteResult(deleted = 2, failed = 1)))
                ?.map { resourceName(it) },
        )
        assertEquals(null, mediaDeletionMessage(MediaDeletionState.Done(0, CloudDeleteResult(0, 0))))
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
                    200 to """{"filename":"/Photos/Поход $id.jpg","basename":"Поход $id.jpg"}"""
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

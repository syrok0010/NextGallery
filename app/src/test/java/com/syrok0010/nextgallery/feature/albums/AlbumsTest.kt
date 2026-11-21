package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.*
import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.memories.MemoriesPhotoDto
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.collection.ViewerSequenceController
import com.syrok0010.nextgallery.feature.collection.toMediaSlots
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.timeline.remote.toMediaItem
import com.syrok0010.nextgallery.feature.upload.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class AlbumsTest {
    @Test fun `partial addition applies only confirmed ids and survives an older read`() = scenario { server, scope ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val albums = server.albums(scope, source = AlbumContentsSource { _, _ -> flow {
            if (reads.incrementAndGet() == 2) {
                started.complete(Unit)
                release.await()
            }
            emit(AlbumContentsBatch(listOf(second), 1))
        } })
        val contents = albums.observeContents(album)
        contents.first { !it.loading && it.loaded == 1 }
        albums.refresh(album)
        started.await()
        assertTrue(albums.add(AlbumTarget.Existing(album, "album"), AlbumAdditionRequest.of(listOf(first, third))))
        contents.first { it.items.any { item -> item.mediaId == first.mediaId } }
        release.complete(Unit)
        val after = contents.first { !it.loading }
        assertEquals(setOf(first.mediaId, second.mediaId), after.items.map { it.mediaId }.toSet())
        assertFalse(after.items.any { it.mediaId == third.mediaId })
        assertEquals(2, reads.get())
    }

    @Test fun `unknown added metadata rereads only its album`() = scenario { server, scope ->
        val other = AlbumLocation.Remote("anna/other")
        val targetReads = AtomicInteger()
        val otherReads = AtomicInteger()
        val albums = server.albums(scope, source = AlbumContentsSource { location, _ -> flow {
            if (location == album) {
                val read = targetReads.incrementAndGet()
                emit(AlbumContentsBatch(if (read == 1) listOf(second) else listOf(first, second), if (read == 1) 1 else 2))
            } else {
                otherReads.incrementAndGet()
                emit(AlbumContentsBatch(listOf(second), 1))
            }
        } })
        val contents = albums.observeContents(album)
        val unrelated = albums.observeContents(other)
        contents.first { !it.loading && it.loaded == 1 }
        unrelated.first { !it.loading && it.loaded == 1 }
        albums.add(AlbumTarget.Existing(album, "album"), AlbumAdditionRequest(listOf(1)))
        contents.first { !it.loading && it.loaded == 2 }
        assertEquals(2, targetReads.get())
        assertEquals(1, otherReads.get())
    }

    @Test fun `metadata reconciliation cannot undo the known part of a confirmed addition`() = scenario { server, scope ->
        val reads = AtomicInteger()
        val reconciled = CompletableDeferred<Unit>()
        val albums = server.albums(scope, source = AlbumContentsSource { _, _ -> flow {
            val read = reads.incrementAndGet()
            emit(AlbumContentsBatch(listOf(second), 1))
            if (read == 2) reconciled.complete(Unit)
        } })
        val contents = albums.observeContents(album)
        contents.first { !it.loading && it.loaded == 1 }
        albums.add(AlbumTarget.Existing(album, "album"), AlbumAdditionRequest(listOf(1, 4), items = listOf(first)))
        reconciled.await()
        val after = contents.first { !it.loading && it.items.size == 2 }
        assertEquals(setOf(first.mediaId, second.mediaId), after.items.map { it.mediaId }.toSet())
        assertEquals(2, reads.get())
    }

    @Test fun `confirmed upload without metadata stays pending through lagging reads and resolves on reopening`() = scenario { server, scope ->
        val reads = AtomicInteger()
        val albums = server.albums(scope, source = AlbumContentsSource { _, _ -> flow {
            if (reads.incrementAndGet() < 3) emit(AlbumContentsBatch(emptyList(), 0))
            else emit(AlbumContentsBatch(listOf(first), 1))
        } }, catalog = { RemoteAlbums(listOf(summary.copy(count = 0))) })
        val contents = albums.observeContents(album)
        contents.first { !it.loading }
        val local = MediaAssetRef.LocalContent("content://media/external/images/media/1", 1, 1)
        val selection = UploadSelection(local, "upload.jpg", "image/jpeg")
        val job = UploadJob("upload", selection, UploadAlbum("anna", "album"))
        val uploaded = UploadedFile(selection, DavFilePath.of("anna", "/Photos/1.jpg"), true, 1)
        assertTrue(AlbumUploadFollowUp(albums).run(server.credentials, job, uploaded, false))
        val pending = contents.first { !it.loading && it.pendingMetadata == 1 }
        assertTrue(pending.items.isEmpty())
        assertEquals(1, pending.total)
        assertFalse(pending.failed)
        albums.state.first { it.remote.items.singleOrNull()?.count == 1 }
        albums.observeContents(album)
        val resolved = contents.first { !it.loading && it.loaded == 1 }
        assertEquals(listOf(first), resolved.items)
        assertEquals(0, resolved.pendingMetadata)
        assertEquals(3, reads.get())
    }

    @Test fun `shared index can materialize confirmed membership without another server read`() = scenario { server, scope ->
        val library = MediaLibraryIndex()
        val albums = server.albums(scope, library = library,
            source = AlbumContentsSource { _, _ -> flowOf(AlbumContentsBatch(emptyList(), 0)) })
        val contents = albums.observeContents(album)
        contents.first { !it.loading }
        albums.add(AlbumTarget.Existing(album, "album"), AlbumAdditionRequest(listOf(1)))
        contents.first { !it.loading && it.pendingMetadata == 1 }
        library.publish(listOf(first))
        val resolved = contents.first { it.pendingMetadata == 0 && it.loaded == 1 }
        assertEquals(listOf(first), resolved.items)
        assertEquals(1, resolved.total)
    }

    @Test fun `explicit authoritative refresh can retire pending membership`() = scenario { server, scope ->
        val albums = server.albums(scope,
            source = AlbumContentsSource { _, _ -> flowOf(AlbumContentsBatch(emptyList(), 0)) })
        val contents = albums.observeContents(album)
        contents.first { !it.loading }
        albums.add(AlbumTarget.Existing(album, "album"), AlbumAdditionRequest(listOf(1)))
        contents.first { !it.loading && it.pendingMetadata == 1 }
        albums.refresh(album)
        val refreshed = contents.first { !it.loading && it.pendingMetadata == 0 }
        assertEquals(0, refreshed.total)
        assertTrue(refreshed.items.isEmpty())
    }

    @Test fun `empty folder clears permission requirement after full access is restored`() = scenario { server, scope ->
        val permission = MutableStateFlow<LocalMediaPermissionMode?>(LocalMediaPermissionMode.Full)
        val reads = AtomicInteger()
        val albums = server.albums(scope, permission = permission, source = AlbumContentsSource { _, _ -> flow {
            reads.incrementAndGet()
            emit(AlbumContentsBatch(emptyList(), 0))
        } })
        val contents = albums.observeContents(AlbumLocation.Folder("external_primary", "Pictures/"))
        contents.first { !it.loading && !it.permissionRequired }
        permission.value = LocalMediaPermissionMode.Partial
        contents.first { it.permissionRequired }
        permission.value = LocalMediaPermissionMode.Full
        val restored = contents.first { !it.loading && !it.permissionRequired }
        assertTrue(restored.items.isEmpty())
        assertEquals(0, restored.total)
        assertFalse(restored.failed)
        assertEquals(2, reads.get())
    }

    @Test fun `failed refresh retains membership and retry can confirm empty album`() = scenario { server, scope ->
        val reads = AtomicInteger()
        val albums = server.albums(scope, source = AlbumContentsSource { _, _ -> flow {
            val read = reads.incrementAndGet()
            emit(AlbumContentsBatch(emptyList(), if (read < 3) 2 else 0))
            if (read == 2) error("offline after index")
            if (read == 1) emit(AlbumContentsBatch(listOf(first, second), 2))
        } })
        val contents = albums.observeContents(album)
        contents.first { !it.loading && it.loaded == 2 }
        albums.refresh(album)
        val failed = contents.first { it.failed }
        assertEquals(setOf(first.mediaId, second.mediaId), failed.items.map { it.mediaId }.toSet())
        albums.refresh(album)
        val empty = contents.first { !it.loading && !it.failed && it.items.isEmpty() }
        assertEquals(0, empty.total)
    }

    @Test fun `upload follow up updates known membership without refreshing`() = scenario { server, scope ->
        val local = first.copy(assetRef = MediaAssetRef.LocalContent("content://media/external/images/media/1", 1, 1))
        val library = MediaLibraryIndex().apply { publish(listOf(local)) }
        val reads = AtomicInteger()
        val albums = server.albums(scope, library = library, source = AlbumContentsSource { _, _ -> flow {
            reads.incrementAndGet()
            emit(AlbumContentsBatch(listOf(second), 1))
        } })
        val contents = albums.observeContents(album)
        contents.first { !it.loading && it.loaded == 1 }
        val selection = requireNotNull(uploadSelection(local))
        val job = UploadJob("upload", selection, UploadAlbum("anna", "album"))
        val uploaded = UploadedFile(selection, DavFilePath.of("anna", "/Photos/1.jpg"), true, 1)
        assertTrue(AlbumUploadFollowUp(albums).run(server.credentials, job, uploaded, false))
        val after = contents.first { it.items.size == 2 }
        assertEquals(1L, after.items.single { it.mediaId == first.mediaId }.remoteFileId)
        assertEquals(1, reads.get())
    }

    @Test fun `uploaded local metadata yields to matching canonical cloud metadata without refresh`() = scenario { server, scope ->
        val local = first.copy(dayId = 19, takenAtEpochSeconds = 1_641_600,
            assetRef = MediaAssetRef.LocalContent("content://media/external/images/media/1", 1, 1))
        val library = MediaLibraryIndex().apply { publish(listOf(local)) }
        val reads = AtomicInteger()
        val albums = server.albums(scope, library = library, source = AlbumContentsSource { _, _ -> flow {
            reads.incrementAndGet()
            emit(AlbumContentsBatch(listOf(second), 1))
        } })
        val contents = albums.observeContents(album)
        contents.first { !it.loading && it.loaded == 1 }
        val selection = requireNotNull(uploadSelection(local))
        val job = UploadJob("upload", selection, UploadAlbum("anna", "album"))
        val file = UploadedFile(selection, DavFilePath.of("anna", "/Photos/1.jpg"), true, 1)
        assertTrue(AlbumUploadFollowUp(albums).run(server.credentials, job, file, false))
        contents.first { it.items.size == 2 }
        val cloud = first.copy(dayId = 21, takenAtEpochSeconds = 1_814_400,
            isFavorite = true, indexedAtEpochSeconds = 999, etag = "cloud-revision")
        library.publish(listOf(cloud))
        val after = contents.first { state -> state.items.any { it.indexedAtEpochSeconds == 999L } }
        val uploaded = after.items.single { it.mediaId == first.mediaId }
        assertEquals(cloud.dayId, uploaded.dayId)
        assertEquals(cloud.takenAtEpochSeconds, uploaded.takenAtEpochSeconds)
        assertTrue(uploaded.isFavorite)
        assertEquals(cloud.etag, uploaded.etag)
        assertEquals(local.localCopy, uploaded.localCopy)
        assertEquals(cloud.remoteCopy, uploaded.remoteCopy)
        assertEquals(listOf(first.mediaId, second.mediaId), after.items.map { it.mediaId })
        assertEquals(1, reads.get())
    }

    @Test fun `permission loss cancels folder read and gaining permission reloads it`() = scenario { server, scope ->
        val permission = MutableStateFlow<LocalMediaPermissionMode?>(LocalMediaPermissionMode.Denied)
        val cancelled = CompletableDeferred<Unit>()
        val albums = server.albums(scope, permission = permission, source = AlbumContentsSource { _, _ -> flow {
            emit(AlbumContentsBatch(listOf(first), 1))
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        } })
        val contents = albums.observeContents(AlbumLocation.Folder("external_primary", "Pictures/"))
        contents.first { it.permissionRequired }
        permission.value = LocalMediaPermissionMode.Full
        contents.first { it.loaded == 1 }
        permission.value = LocalMediaPermissionMode.Partial
        cancelled.await()
        val blocked = contents.first { it.permissionRequired }
        assertTrue(blocked.items.isEmpty())
    }

    private fun scenario(block: suspend (NextcloudFixture, CoroutineScope) -> Unit) = runBlocking {
        NextcloudFixture { request -> when (request.method) {
            "PROPFIND" -> 207 to found()
            "DELETE" -> (if (request.decodedTarget.endsWith("2-b.jpg")) 403 else 204) to ""
            "SEARCH" -> 207 to found(request.searchedFileId to "Photos/${request.searchedFileId}.jpg")
            "COPY" -> (if (request.decodedTarget.endsWith("3.jpg")) 403 else 201) to ""
            else -> 500 to ""
        } }.use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try { withTimeout(5_000) { block(server, scope) } } finally { scope.cancel() }
        }
    }

    private companion object {
        val album = AlbumLocation.Remote("anna/album")
        val first = MemoriesPhotoDto(1, 20, epoch = 1_728_001).toMediaItem(MediaId("first"))
        val second = MemoriesPhotoDto(2, 20, epoch = 1_728_002).toMediaItem(MediaId("second"))
        val third = MemoriesPhotoDto(3, 20, epoch = 1_728_003).toMediaItem(MediaId("third"))
        val summary = AlbumSummary("remote:1", "album", AlbumOrigin.Nextcloud, 2, "", AlbumCover.Remote(1, null), album)
    }
}

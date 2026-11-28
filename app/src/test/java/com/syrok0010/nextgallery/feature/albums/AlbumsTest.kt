package com.syrok0010.nextgallery.feature.albums

import androidx.lifecycle.ViewModelStore
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.media.remoteCopy
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.memories.MemoriesPhotoDto
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.library.LocalLibraryStatus
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaIndexProgress
import com.syrok0010.nextgallery.feature.timeline.remote.toMediaItem
import com.syrok0010.nextgallery.feature.upload.EnqueueResult
import com.syrok0010.nextgallery.feature.upload.UploadAlbum
import com.syrok0010.nextgallery.feature.upload.UploadJob
import com.syrok0010.nextgallery.feature.upload.UploadSelection
import com.syrok0010.nextgallery.feature.upload.UploadedFile
import com.syrok0010.nextgallery.feature.upload.uploadSelection
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumsTest {
    @Test fun `remote catalog failure preserves local folders and refresh recovers both`() = scenario { harness, scope ->
        var failRemote = true
        val localRefreshes = AtomicInteger()
        val library = MediaLibraryIndex().apply {
            publish(listOf(localFolderItem("cam-1", "external_primary", "DCIM/Camera/", 10)))
        }
        val albums = harness.albums(
            scope,
            library = library,
            catalog = {
                if (failRemote) error("offline")
                RemoteAlbumsCatalog(listOf(summary))
            },
            refreshLocal = { localRefreshes.incrementAndGet() },
        )
        library.publishLocalStatus(LocalLibraryStatus(hasAccess = true))
        val partial = albums.catalog.first { it.remote.failed && it.local.items.size == 1 }
        assertEquals(listOf("Camera"), partial.items.map { it.name })

        failRemote = false
        albums.refreshCatalog()
        val recovered = albums.catalog.first { !it.remote.failed && it.items.size == 2 }
        assertEquals(2, recovered.items.size)
        assertEquals(1, localRefreshes.get())

        library.publishLocalStatus(LocalLibraryStatus(hasAccess = false))
        val withoutLocal = albums.catalog.first { it.local.items.isEmpty() }
        assertEquals(1, withoutLocal.remote.items.size)
    }

    @Test fun `local indexing status exposes loading state in catalog and open folder`() = scenario { harness, scope ->
        val library = MediaLibraryIndex()
        val albums = harness.albums(scope, library = library)
        val folder = AlbumLocation.Folder("external_primary", "Pictures/")

        library.publishLocalStatus(
            LocalLibraryStatus(
                hasAccess = true,
                loading = true,
                initial = true,
                progress = LocalMediaIndexProgress(indexedCount = 200, totalCount = 1_000),
            ),
        )
        val catalogLoading = albums.catalog.first { it.local.loading }
        assertTrue(catalogLoading.local.loading)

        val folderLoading = albums.observeContents(folder).first { it.loading }
        assertTrue(folderLoading.items.isEmpty())

        library.publish(listOf(localFolderItem("pic-1", "external_primary", "Pictures/", 20)))
        library.publishLocalStatus(LocalLibraryStatus(hasAccess = true, loading = false))

        val catalogReady = albums.catalog.first { !it.local.loading && it.local.items.size == 1 }
        assertEquals("Pictures", catalogReady.local.items.single().name)
        val folderReady = albums.observeContents(folder).first { !it.loading && it.loaded == 1 }
        assertEquals(listOf(MediaId("pic-1")), folderReady.items.map { it.mediaId })
    }

    @Test fun `partial addition applies only confirmed ids and cancels an older in-flight read`() = scenario { harness, scope ->
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val library = MediaLibraryIndex().apply { publish(listOf(first, third)) }
        val albums = harness.albums(
            scope,
            library = library,
            catalog = { RemoteAlbumsCatalog(listOf(summary.copy(count = 1, cover = AlbumCover.Remote(2, null)))) },
            remoteContents = {
                flow {
                    if (reads.incrementAndGet() == 2) {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                    emit(AlbumContentsBatch(listOf(second), 1))
                }
            },
        )
        val contents = albums.observeContents(album)
        contents.first { !it.loading && it.loaded == 1 }
        albums.refresh(album)
        started.await()
        val addition = requireNotNull(albums.prepareAddition(listOf(first.mediaId, third.mediaId)))
        assertTrue(albums.add(AlbumTarget.Existing(album, "album"), addition))
        cancelled.await()
        val after = contents.first { !it.loading && it.items.any { item -> item.mediaId == first.mediaId } }
        assertEquals(setOf(first.mediaId, second.mediaId), after.items.map { it.mediaId }.toSet())
        assertFalse(after.items.any { it.mediaId == third.mediaId })
        assertEquals(2, albums.catalog.first { it.remote.items.singleOrNull()?.count == 2 }.remote.items.single().count)
        assertEquals(2, reads.get())
    }

    @Test fun `adding to an unopened album refreshes catalog without reading contents`() = scenario { harness, scope ->
        val reads = AtomicInteger()
        val refreshes = AtomicInteger()
        val library = MediaLibraryIndex().apply { publish(listOf(first)) }
        val albums = harness.albums(
            scope,
            library = library,
            remoteContents = {
                reads.incrementAndGet()
                flowOf(AlbumContentsBatch(emptyList(), 0))
            },
            catalog = {
                refreshes.incrementAndGet()
                RemoteAlbumsCatalog(emptyList())
            },
        )
        val initial = refreshes.get()
        assertTrue(
            albums.add(
                AlbumTarget.Existing(album, "album"),
                requireNotNull(albums.prepareAddition(listOf(first.mediaId))),
            ),
        )
        albums.operations.first { it.singleOrNull()?.running == false }
        while (refreshes.get() == initial) delay(10)
        assertEquals(0, reads.get())
    }

    @Test fun `unknown added metadata reloads open album without touching unrelated albums`() = scenario { harness, scope ->
        val other = AlbumLocation.Remote("anna/other")
        val targetReads = AtomicInteger()
        val otherReads = AtomicInteger()
        val albums = harness.albums(
            scope,
            remoteContents = { location ->
                flow {
                    if (location == album) {
                        val read = targetReads.incrementAndGet()
                        emit(
                            AlbumContentsBatch(
                                if (read == 1) listOf(second) else listOf(first, second),
                                if (read == 1) 1 else 2,
                            ),
                        )
                    } else {
                        otherReads.incrementAndGet()
                        emit(AlbumContentsBatch(listOf(second), 1))
                    }
                }
            },
        )
        val collector = scope.launch { albums.observeContents(album).collect {} }
        try {
            albums.observeContents(album).first { !it.loading && it.loaded == 1 }
            albums.observeContents(other).first { !it.loading && it.loaded == 1 }
            albums.add(
                AlbumTarget.Existing(album, "album"),
                requireNotNull(AlbumAdditionPreparation.of(listOf(first))),
            )
            albums.observeContents(album).first { !it.loading && it.loaded == 2 }
            assertEquals(2, targetReads.get())
            assertEquals(1, otherReads.get())
        } finally {
            collector.cancel()
        }
    }

    @Test fun `upload follow up uses known canonical metadata without re-reading`() = scenario { harness, scope ->
        val local = first.copy(assetRef = MediaAssetRef.LocalContent("content://media/external/images/media/1", 1, 1))
        val canonical = first.copy(
            assetRef = MediaAssetRef.LocalFirst(requireNotNull(local.localCopy), requireNotNull(first.remoteCopy)),
        )
        val library = MediaLibraryIndex().apply { publish(listOf(canonical)) }
        val reads = AtomicInteger()
        val albums = harness.albums(
            scope,
            library = library,
            remoteContents = {
                flow {
                    reads.incrementAndGet()
                    emit(AlbumContentsBatch(listOf(second), 1))
                }
            },
        )
        val contents = albums.observeContents(album)
        contents.first { !it.loading && it.loaded == 1 }
        val selection = requireNotNull(uploadSelection(local))
        val job = UploadJob("upload", selection, UploadAlbum("anna", "album"))
        val uploaded = UploadedFile(selection, DavFilePath.of("anna", "/Photos/1.jpg"), true, 1)
        val accepted = withContext(Dispatchers.Default) {
            AlbumUploadFollowUp(albums).run(harness.credentials, job, uploaded, false)
        }
        assertTrue(accepted)
        val after = contents.first { it.items.size == 2 }
        assertEquals(1L, after.items.single { it.mediaId == first.mediaId }.remoteFileId)
        assertEquals(1, reads.get())
    }

    @Test fun `upload without canonical metadata marks closed album stale and reloads on reopen`() = scenario { harness, scope ->
        val reads = AtomicInteger()
        val albums = harness.albums(
            scope,
            remoteContents = {
                flow {
                    if (reads.incrementAndGet() == 1) emit(AlbumContentsBatch(emptyList(), 0))
                    else emit(AlbumContentsBatch(listOf(first), 1))
                }
            },
        )
        albums.observeContents(album).first { !it.loading }
        assertEquals(1, reads.get())
        val selection = UploadSelection(
            MediaAssetRef.LocalContent("content://media/external/images/media/1", 1, 1),
            "upload.jpg",
            "image/jpeg",
        )
        val job = UploadJob("upload", selection, UploadAlbum("anna", "album"))
        val uploaded = UploadedFile(selection, DavFilePath.of("anna", "/Photos/1.jpg"), true, 1)
        assertTrue(
            withContext(Dispatchers.Default) {
                AlbumUploadFollowUp(albums).run(harness.credentials, job, uploaded, false)
            },
        )
        // Album was closed, so upload does not fetch contents in the background.
        assertEquals(1, reads.get())
        val reopened = albums.observeContents(album).first { !it.loading && it.loaded == 1 }
        assertEquals(listOf(first), reopened.items)
        assertEquals(2, reads.get())
    }

    @Test fun `failed refresh retains visible items and retry can confirm empty album`() = scenario { harness, scope ->
        val reads = AtomicInteger()
        val albums = harness.albums(
            scope,
            remoteContents = {
                flow {
                    when (reads.incrementAndGet()) {
                        1 -> emit(AlbumContentsBatch(listOf(first, second), 2))
                        2 -> {
                            emit(AlbumContentsBatch(emptyList(), 2))
                            error("offline after index")
                        }
                        else -> emit(AlbumContentsBatch(emptyList(), 0))
                    }
                }
            },
        )
        val contents = albums.observeContents(album)
        contents.first { !it.loading && it.loaded == 2 }
        albums.refresh(album)
        val failed = contents.first { it.failed }
        assertEquals(setOf(first.mediaId, second.mediaId), failed.items.map { it.mediaId }.toSet())
        albums.refresh(album)
        val empty = contents.first { !it.loading && !it.failed && it.items.isEmpty() }
        assertEquals(0, empty.total)
    }

    @Test fun `completed read with mismatched total succeeds without failing`() = scenario { harness, scope ->
        val albums = harness.albums(
            scope,
            remoteContents = { flowOf(AlbumContentsBatch(listOf(first), total = 2, loaded = 1)) },
        )
        val state = albums.observeContents(album).first { !it.loading }
        assertFalse(state.failed)
        assertEquals(listOf(first), state.items)
        assertEquals(1, state.total)
    }

    @Test fun `permission loss hides folder contents and gaining permission restores them`() = scenario { harness, scope ->
        val library = MediaLibraryIndex().apply {
            publish(listOf(localFolderItem("pic-1", "external_primary", "Pictures/", 10)))
            publishLocalStatus(LocalLibraryStatus(hasAccess = false))
        }
        val albums = harness.albums(
            scope,
            library = library,
        )
        val contents = albums.observeContents(AlbumLocation.Folder("external_primary", "Pictures/"))
        contents.first { it.permissionRequired }
        library.publishLocalStatus(LocalLibraryStatus(hasAccess = true))
        contents.first { it.loaded == 1 }
        library.publishLocalStatus(LocalLibraryStatus(hasAccess = false))
        val blocked = contents.first { it.permissionRequired }
        assertTrue(blocked.items.isEmpty())
    }

    @Test fun `empty folder clears permission requirement after full access is restored`() = scenario { harness, scope ->
        val library = MediaLibraryIndex().apply {
            publishLocalStatus(LocalLibraryStatus(hasAccess = true))
        }
        val albums = harness.albums(
            scope,
            library = library,
        )
        val contents = albums.observeContents(AlbumLocation.Folder("external_primary", "Pictures/"))
        contents.first { !it.loading && !it.permissionRequired }
        library.publishLocalStatus(LocalLibraryStatus(hasAccess = false))
        contents.first { it.permissionRequired }
        library.publishLocalStatus(LocalLibraryStatus(hasAccess = true))
        val restored = contents.first { !it.loading && !it.permissionRequired }
        assertTrue(restored.items.isEmpty())
        assertEquals(0, restored.total)
        assertFalse(restored.failed)
    }

    @Test fun `removing from an unopened album refreshes catalog without reading contents`() = scenario { harness, scope ->
        val reads = AtomicInteger()
        val refreshes = AtomicInteger()
        val albums = harness.albums(
            scope,
            remoteContents = {
                reads.incrementAndGet()
                flowOf(AlbumContentsBatch(emptyList(), 0))
            },
            catalog = {
                refreshes.incrementAndGet()
                RemoteAlbumsCatalog(emptyList())
            },
        )
        val initial = refreshes.get()
        assertTrue(albums.removeMembers(album, "album", setOf(1L)))
        albums.operations.first { it.singleOrNull()?.running == false }
        while (refreshes.get() == initial) delay(10)
        assertEquals(0, reads.get())
    }

    @Test fun `partial removal updates open album and catalog and cancels an older in-flight read`() = scenario { harness, scope ->
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val albums = harness.albums(
            scope,
            catalog = { RemoteAlbumsCatalog(listOf(summary)) },
            remoteContents = {
                flow {
                    if (reads.incrementAndGet() == 2) {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                    emit(AlbumContentsBatch(listOf(first, second), 2))
                }
            },
            remove = { _, fileIds ->
                AlbumRemovalBatch(removedIds = fileIds - 2L, failed = if (2L in fileIds) 1 else 0)
            },
        )
        val contents = albums.observeContents(album)
        contents.first { !it.loading && it.loaded == 2 }
        albums.refresh(album)
        started.await()
        assertTrue(albums.removeMembers(album, "album", setOf(1L, 2L)))
        cancelled.await()
        val after = contents.first { !it.loading && it.items.map { item -> item.mediaId } == listOf(second.mediaId) }
        assertEquals(1, after.total)
        val projected = albums.catalog.first { it.remote.items.singleOrNull()?.count == 1 }.remote.items.single()
        assertEquals(AlbumCover.Remote(2L, null), projected.cover)
    }

    @Test fun `consumeSucceeded clears succeeded addition while keeping failed removal`() = scenario { harness, scope ->
        val albums = harness.albums(
            scope,
            remove = { _, fileIds -> AlbumRemovalBatch(removedIds = emptySet(), failed = fileIds.size) },
        )
        assertTrue(albums.add(AlbumTarget.Existing(album, "album"), AlbumAdditionRequest.of(listOf(first))))
        assertTrue(albums.removeMembers(album, "album", setOf(2L)))
        albums.operations.first { ops -> ops.size == 2 && ops.all { !it.running } }
        albums.consumeSucceeded()
        val remaining = albums.operations.first { it.size == 1 }.single()
        assertTrue(remaining.failed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `viewModel tracks departed items and allows actions on folders and owned albums only`() = scenario { harness, scope ->
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val reads = AtomicInteger()
            val albums = harness.albums(
                scope,
                remoteContents = {
                    reads.incrementAndGet()
                    flowOf(AlbumContentsBatch(listOf(first, second), 2))
                },
            )
            val store = ViewModelStore()
            val ownedVm = AlbumContentsViewModel(album, albums).also { store.put("owned", it) }
            val folderVm = AlbumContentsViewModel(
                AlbumLocation.Folder("external_primary", "Pictures/"),
                albums,
            ).also { store.put("folder", it) }
            val foreignVm = AlbumContentsViewModel(AlbumLocation.Remote("bob/shared"), albums)
                .also { store.put("foreign", it) }
            assertTrue(ownedVm.canModify)
            assertTrue(ownedVm.supportsActions)
            assertFalse(folderVm.canModify)
            assertTrue(folderVm.supportsActions)
            assertFalse(foreignVm.canModify)
            assertFalse(foreignVm.supportsActions)
            // Constructing the ViewModel must not keep an eager subscription to album contents.
            assertEquals(0, reads.get())
            val departedCollector = scope.launch { ownedVm.departedIds.collect {} }
            try {
                ownedVm.state.first { !it.loading && it.items.size == 2 }
                assertTrue(ownedVm.removeMembers("album", listOf(first)))
                val departed = ownedVm.departedIds.first { first.mediaId in it }
                assertEquals(setOf(first.mediaId), departed)
                assertTrue(ownedVm.addToAlbum(AlbumTarget.Existing(album, "album"), AlbumAdditionRequest.of(listOf(first))))
                val restored = ownedVm.departedIds.first { first.mediaId !in it }
                assertTrue(restored.isEmpty())
            } finally {
                departedCollector.cancelAndJoin()
                store.clear()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `cloud deletion removes confirmed items from open remote albums and updates catalog`() = scenario { harness, scope ->
        val catalogReads = AtomicInteger()
        val albums = harness.albums(
            scope,
            catalog = {
                catalogReads.incrementAndGet()
                RemoteAlbumsCatalog(listOf(summary))
            },
            remoteContents = { flowOf(AlbumContentsBatch(listOf(first, second), 2)) },
        )
        val contents = albums.observeContents(album)
        contents.first { !it.loading && it.loaded == 2 }
        albums.catalog.first { it.remote.items.singleOrNull()?.count == 2 }
        val before = catalogReads.get()

        albums.onCloudDeleted(setOf(1L))

        val after = contents.first { it.items.map { item -> item.mediaId } == listOf(second.mediaId) }
        assertEquals(1, after.total)
        while (catalogReads.get() == before) delay(10)
        val projected = albums.catalog.first { it.remote.items.singleOrNull()?.count == 1 }.remote.items.single()
        assertEquals(AlbumCover.Remote(2L, null), projected.cover)
    }

    private class Harness {
        val credentials = AccountCredentials("https://cloud.example", "anna", "secret", "anna")

        fun albums(
            scope: CoroutineScope,
            library: MediaLibraryIndex = MediaLibraryIndex().apply {
                publishLocalStatus(LocalLibraryStatus(hasAccess = true))
            },
            catalog: suspend () -> RemoteAlbumsCatalog = { RemoteAlbumsCatalog(emptyList()) },
            remoteContents: (AlbumLocation.Remote) -> Flow<AlbumContentsBatch> = {
                flowOf(AlbumContentsBatch(emptyList(), 0))
            },
            enqueue: suspend (Collection<MediaId>, UploadAlbum) -> EnqueueResult = { ids, _ ->
                EnqueueResult.Accepted(ids.size)
            },
            refreshLocal: () -> Unit = {},
            remove: suspend (AlbumAddress, Set<Long>) -> AlbumRemovalBatch = { _, ids ->
                AlbumRemovalBatch(ids, 0)
            },
        ): Albums {
            val remote = object : RemoteAlbums {
                override suspend fun loadCatalog() = catalog()
                override fun loadContents(location: AlbumLocation.Remote) = remoteContents(location)
                override suspend fun create(name: String) = AlbumLocation.Remote("anna/$name")
                override suspend fun add(album: AlbumAddress, fileIds: List<Long>) =
                    AlbumWriteResult(
                        fileIds.distinct().associateWith {
                            if (it == 3L) AlbumCopyOutcome.Failed else AlbumCopyOutcome.Added
                        },
                    )

                override suspend fun addFile(file: DavFilePath, album: AlbumAddress) =
                    AlbumCopyOutcome.Added

                override suspend fun containsFile(album: AlbumAddress, fileId: Long?) =
                    fileId != null

                override suspend fun remove(album: AlbumAddress, fileIds: Set<Long>) =
                    remove(album, fileIds)
            }
            return Albums(credentials, remote, library, scope, enqueue, refreshLocal)
        }
    }

    private fun scenario(block: suspend (Harness, CoroutineScope) -> Unit) = runBlocking {
        val harness = Harness()
        val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        try {
            withTimeout(5_000) { block(harness, scope) }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        val album = AlbumLocation.Remote("anna/album")
        val first = MemoriesPhotoDto(1, 20, epoch = 1_728_001).toMediaItem(MediaId("first"))
        val second = MemoriesPhotoDto(2, 20, epoch = 1_728_002).toMediaItem(MediaId("second"))
        val third = MemoriesPhotoDto(3, 20, epoch = 1_728_003).toMediaItem(MediaId("third"))
        val summary = AlbumSummary(
            "remote:1",
            "album",
            AlbumOrigin.Nextcloud,
            2,
            "",
            AlbumCover.Remote(1, null),
            album,
        )

        fun localFolderItem(id: String, volume: String, path: String, modified: Long) =
            first.copy(
                mediaId = MediaId(id),
                takenAtEpochSeconds = modified,
                assetRef = MediaAssetRef.LocalContent(
                    contentUri = "content://media/external/images/media/$id",
                    modifiedAtEpochSeconds = modified,
                    sizeBytes = 1,
                    volumeName = volume,
                    relativePath = path,
                ),
            )
    }
}

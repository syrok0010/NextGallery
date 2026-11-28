package com.syrok0010.nextgallery.feature.albums

import androidx.annotation.MainThread
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.remoteCopy
import com.syrok0010.nextgallery.core.operation.OperationStatusSnapshot
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.upload.EnqueueResult
import com.syrok0010.nextgallery.feature.upload.UploadAlbum
import com.syrok0010.nextgallery.feature.upload.UploadedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns session-scoped album catalog, loaded remote album contents cache, and confirmed album mutations.
 * Device folders are projected directly from [library] without running a second MediaStore scan.
 * Synchronous commands run on main, as does [scope]; upload workers enter it through [addUploaded].
 */
internal class Albums(
    private val credentials: AccountCredentials,
    private val remote: RemoteAlbums,
    private val library: MediaLibraryIndex,
    private val scope: CoroutineScope,
    private val enqueueUploads: suspend (Collection<MediaId>, UploadAlbum) -> EnqueueResult,
    private val refreshLocal: () -> Unit = {},
) : OperationStatusSource {
    private val mutations = Mutex()
    private val catalogRefreshes = MutableStateFlow(0)
    private val mutableCatalog = MutableStateFlow(AlbumsUiState())
    private val entries = mutableMapOf<AlbumLocation.Remote, Entry>()
    private val additionState = MutableStateFlow<AlbumAdditionState>(AlbumAdditionState.Idle)
    private var additionJob: Job? = null

    val catalog: StateFlow<AlbumsUiState> = mutableCatalog.asStateFlow()
    val state: StateFlow<AlbumsUiState> get() = catalog
    val adding: StateFlow<Boolean> = additionState
        .map { it is AlbumAdditionState.Running }
        .stateIn(scope, SharingStarted.Eagerly, false)
    override val operations: StateFlow<List<OperationStatusSnapshot>> = additionState
        .map(::albumOperationSnapshots)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private class Entry(initial: AlbumContentsState) {
        val state = MutableStateFlow(initial)
        var loaded = false
        var stale = false
        var job: Job? = null
        var generation = 0L
    }

    init {
        scope.launch {
            catalogRefreshes.collectLatest { loadRemoteCatalog() }
        }
        scope.launch {
            combine(library.state, library.localStatus) { known, status ->
                val local = if (!status.hasAccess) {
                    AlbumSourceState()
                } else {
                    AlbumSourceState(
                        items = localAlbumSummaries(known.values),
                        loading = status.loading,
                        failed = status.failed,
                    )
                }
                mutableCatalog.update { it.copy(local = local, localAccess = status.hasAccess) }
            }.collect()
        }
    }

    @MainThread
    fun observeContents(location: AlbumLocation): Flow<AlbumContentsState> =
        when (location) {
            is AlbumLocation.Folder -> combine(
                library.state,
                library.localStatus,
            ) { known, status ->
                if (!status.hasAccess) {
                    AlbumContentsState(permissionRequired = true)
                } else {
                    val items = folderItems(known.values, location)
                    AlbumContentsState(
                        items = items,
                        total = items.size,
                        loaded = items.size,
                        loading = status.loading,
                        failed = status.failed,
                    )
                }
            }

            is AlbumLocation.Remote -> flow {
                val entry = entry(location)
                if ((!entry.loaded || entry.stale) && entry.job?.isActive != true) {
                    load(location, entry)
                }
                emitAll(
                    combine(entry.state, library.state) { current, known ->
                        val projected = current.items
                            .map { member -> known[member.mediaId] ?: member }
                            .distinctBy { it.mediaId }
                            .sortedWith(albumItemOrder)
                        current.copy(items = projected)
                    },
                )
            }
        }

    @MainThread
    fun canModify(location: AlbumLocation): Boolean =
        location is AlbumLocation.Remote && AlbumAddress.of(location)?.owner == credentials.userId

    fun writableAlbums(state: AlbumsUiState): List<AlbumSummary> =
        writableAlbums(state.remote.items, credentials.userId)

    @MainThread
    fun refreshCatalog() {
        catalogRefreshes.update { it + 1 }
        refreshLocal()
    }

    @MainThread
    fun refresh(location: AlbumLocation) {
        when (location) {
            is AlbumLocation.Folder -> refreshLocal()
            is AlbumLocation.Remote -> entries[location]?.let { load(location, it) }
        }
    }

    /** Resolves selected [mediaIds] into an addition plan before opening the album picker. */
    @MainThread
    fun prepareAddition(mediaIds: Collection<MediaId>): AlbumAdditionPreparation? =
        AlbumAdditionPreparation.of(mediaIds.distinct().mapNotNull { library[it] })

    @MainThread
    fun add(target: AlbumTarget, addition: AlbumAdditionPreparation): Boolean {
        if (additionJob?.isActive == true) return false
        additionState.value = AlbumAdditionState.Running(target.name, addition.cloudMediaIds)
        additionJob = scope.launch {
            mutations.withLock { additionState.value = performAddition(target, addition) }
        }
        return true
    }

    /** Worker entry point. Replay checks exact membership before COPY and never repeats the upload. */
    suspend fun addUploaded(target: UploadAlbum, file: UploadedFile, recovered: Boolean): Boolean =
        withContext(scope.coroutineContext.minusKey(Job)) {
            mutations.withLock {
                require(
                    target.owner == credentials.userId,
                ) { "Only owned albums support additions" }
                val album = AlbumAddress(target.owner, target.name)
                val accepted = if (recovered) {
                    remote.containsFile(album, file.fileId) ||
                        remote.addFile(file.path, album) == AlbumCopyOutcome.Added
                } else {
                    remote.addFile(file.path, album) != AlbumCopyOutcome.Failed
                }
                if (accepted) {
                    val id = file.fileId
                    if (id == null) {
                        invalidateOrRefresh(album.toLocation())
                    } else {
                        applyAdded(album.toLocation(), setOf(id))
                    }
                }
                accepted
            }
        }

    @MainThread
    override fun consumeSucceeded() {
        if ((additionState.value as? AlbumAdditionState.Done)?.result?.failed == 0) {
            additionState.value = AlbumAdditionState.Idle
        }
    }

    @MainThread
    override fun consumeAll() {
        if (additionState.value !is AlbumAdditionState.Running) {
            additionState.value = AlbumAdditionState.Idle
        }
    }

    private suspend fun performAddition(
        target: AlbumTarget,
        addition: AlbumAdditionPreparation,
    ): AlbumAdditionState =
        try {
            val location = when (target) {
                is AlbumTarget.Existing -> target.location
                is AlbumTarget.New -> remote.create(target.name.trim()).also { refreshCatalog() }
            }
            val address = AlbumAddress.of(location)
            if (address == null || address.owner != credentials.userId) {
                AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.NotWritable)
            } else {
                val queued = enqueue(addition.uploads, UploadAlbum(address.owner, address.name))
                val result = remote.add(address, addition.fileIds)
                applyAdded(location, result.presentIds)
                AlbumAdditionState.Done(target.name, result.summary, queued)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UploadEnqueueRejected) {
            AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.QueueStorage)
        } catch (_: AlbumAlreadyExistsException) {
            AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.AlbumExists)
        } catch (_: Exception) {
            AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.Network)
        }

    private suspend fun enqueue(ids: Collection<MediaId>, album: UploadAlbum): Int =
        if (ids.isEmpty()) {
            0
        } else {
            when (val result = enqueueUploads(ids, album)) {
                is EnqueueResult.Accepted -> result.count
                EnqueueResult.NothingToUpload -> 0
                EnqueueResult.Rejected -> throw UploadEnqueueRejected()
            }
        }

    private fun applyAdded(location: AlbumLocation.Remote, ids: Set<Long>) {
        if (ids.isEmpty()) return
        val entry = entries[location]
        if (entry == null) {
            refreshCatalog()
            return
        }
        entry.job?.cancel()
        entry.generation++
        val known = (library.state.value.values + entry.state.value.items)
            .mapNotNull { item -> item.remoteFileId?.let { it to item } }
            .toMap()
        val resolved = ids.mapNotNull { known[it] }
        val merged = (entry.state.value.items + resolved).distinctBy { it.mediaId }
        val complete = entry.loaded && resolved.size == ids.size
        entry.state.value = entry.state.value.copy(
            items = merged,
            loaded = merged.size,
            total = if (entry.loaded) merged.size else maxOf(entry.state.value.total, merged.size),
            loading = false,
        )
        if (complete) {
            updateCatalogSummary(location, merged)
        } else {
            entry.stale = true
            refreshCatalog()
            if (entry.state.subscriptionCount.value > 0) load(location, entry)
        }
    }

    private fun invalidateOrRefresh(location: AlbumLocation.Remote) {
        val entry = entries[location]
        if (entry != null) {
            entry.stale = true
            if (entry.state.subscriptionCount.value > 0) load(location, entry)
        }
        refreshCatalog()
    }

    private fun entry(location: AlbumLocation.Remote): Entry =
        entries.getOrPut(location) { Entry(AlbumContentsState(loading = true)) }

    private fun load(location: AlbumLocation.Remote, entry: Entry) {
        entry.job?.cancel()
        val generation = ++entry.generation
        val hadItems = entry.loaded || entry.state.value.items.isNotEmpty()
        entry.state.value = entry.state.value.copy(
            loading = true,
            failed = false,
            permissionRequired = false,
        )
        val stream = remote.loadContents(location)
        entry.job = scope.launch {
            try {
                var latest: AlbumContentsBatch? = null
                stream.collect { batch ->
                    if (entry.generation != generation) return@collect
                    library.observeRemote(batch.items)
                    latest = batch
                    if (!hadItems) {
                        entry.state.value = entry.state.value.copy(
                            items = batch.items,
                            loaded = batch.items.size,
                            total = maxOf(batch.total, batch.items.size),
                        )
                    }
                }
                if (entry.generation == generation) {
                    val items = latest?.items ?: entry.state.value.items
                    entry.loaded = true
                    entry.stale = false
                    entry.state.value = AlbumContentsState(
                        items = items,
                        total = items.size,
                        loaded = items.size,
                    )
                    updateCatalogSummary(location, items)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (entry.generation == generation) {
                    entry.state.value = entry.state.value.copy(loading = false, failed = true)
                }
            }
        }
    }

    private fun updateCatalogSummary(location: AlbumLocation.Remote, items: List<MediaItem>) {
        mutableCatalog.update { current ->
            val updated = current.remote.items.map { album ->
                if (album.location == location) album.withContents(items) else album
            }
            current.copy(remote = current.remote.copy(items = updated))
        }
    }

    private suspend fun loadRemoteCatalog() {
        mutableCatalog.update { it.copy(remote = it.remote.copy(loading = true, failed = false)) }
        try {
            val result = remote.loadCatalog()
            val synced = result.albums.map { album ->
                val location = album.location as? AlbumLocation.Remote ?: return@map album
                val cached = entries[location]?.takeIf {
                    it.loaded && !it.stale
                } ?: return@map album
                album.withContents(cached.state.value.items)
            }
            mutableCatalog.update {
                it.copy(remote = AlbumSourceState(synced, supported = result.supported))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableCatalog.update {
                it.copy(
                    remote = it.remote.copy(loading = false, failed = true),
                )
            }
        }
    }
}

private fun AlbumSummary.withContents(items: List<MediaItem>): AlbumSummary {
    val cover = (cover as? AlbumCover.Remote)?.takeIf { existing ->
        items.any { it.remoteFileId == existing.fileId }
    } ?: items.firstNotNullOfOrNull { item ->
        item.remoteFileId?.let { AlbumCover.Remote(it, item.etag) }
    }
    return copy(count = items.size, cover = cover)
}

private class UploadEnqueueRejected : Exception()

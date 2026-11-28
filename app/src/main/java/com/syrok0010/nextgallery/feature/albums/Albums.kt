package com.syrok0010.nextgallery.feature.albums

import androidx.annotation.MainThread
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.operation.OperationStatusSnapshot
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.upload.EnqueueResult
import com.syrok0010.nextgallery.feature.upload.UploadAlbum
import com.syrok0010.nextgallery.feature.upload.UploadedFile
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
 * Owns session-scoped album catalog, loaded album contents cache, and confirmed album mutations.
 * Synchronous commands run on main, as does [scope]; upload workers enter it through [addUploaded].
 */
@OptIn(FlowPreview::class)
internal class Albums(
    private val credentials: AccountCredentials,
    private val remote: RemoteAlbums,
    private val local: LocalFolders,
    private val library: MediaLibraryIndex,
    private val permission: StateFlow<LocalMediaPermissionMode?>,
    private val scope: CoroutineScope,
    private val enqueueUploads: suspend (Collection<MediaId>, UploadAlbum) -> EnqueueResult,
) : OperationStatusSource {
    private val mutations = Mutex()
    private val catalogRefreshes = MutableStateFlow(0)
    private val mutableCatalog = MutableStateFlow(AlbumsUiState())
    private val entries = mutableMapOf<AlbumLocation, Entry>()
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
            permission.collectLatest { mode ->
                mutableCatalog.update { it.copy(local = AlbumSourceState(), permission = mode) }
                if (mode != LocalMediaPermissionMode.Full) {
                    clearFolders()
                    return@collectLatest
                }
                reloadEmptyFolders()
                coroutineScope {
                    val localChanges = MutableStateFlow(0)
                    launch {
                        local.changes().debounce(450.milliseconds).collect {
                            refreshFolders()
                            localChanges.update { it + 1 }
                        }
                    }
                    combine(catalogRefreshes, localChanges) { refresh, change -> refresh to change }
                        .collectLatest { loadLocalCatalog() }
                }
            }
        }
    }

    @MainThread
    fun observeContents(location: AlbumLocation): Flow<AlbumContentsState> = flow {
        val entry = entry(location)
        if ((!entry.loaded || entry.stale) && entry.job?.isActive != true) {
            load(location, entry)
        }
        emitAll(
            combine(entry.state, library.state, permission) { current, known, access ->
                if (location is AlbumLocation.Folder && access != LocalMediaPermissionMode.Full) {
                    AlbumContentsState(permissionRequired = true)
                } else {
                    current.copy(
                        items = albumMediaProjection(
                            current.items,
                            known,
                            access == LocalMediaPermissionMode.Full,
                        ),
                    )
                }
            },
        )
    }

    @MainThread
    fun canModify(location: AlbumLocation): Boolean =
        location is AlbumLocation.Remote && AlbumAddress.of(location)?.owner == credentials.userId

    fun writableAlbums(state: AlbumsUiState): List<AlbumSummary> =
        writableAlbums(state.remote.items, credentials.userId)

    @MainThread
    fun refreshCatalog() {
        catalogRefreshes.update { it + 1 }
    }

    @MainThread
    fun refresh(location: AlbumLocation) {
        entries[location]?.let { load(location, it) }
    }

    @MainThread
    fun add(target: AlbumTarget, request: AlbumAdditionRequest): Boolean {
        if (additionJob?.isActive == true) return false
        additionState.value = AlbumAdditionState.Running(target.name, request.fileIds.toSet())
        additionJob = scope.launch {
            mutations.withLock { additionState.value = performAddition(target, request) }
        }
        return true
    }

    /** Worker entry point. Replay checks exact membership before COPY and never repeats the upload. */
    suspend fun addUploaded(target: UploadAlbum, file: UploadedFile, recovered: Boolean): Boolean =
        withContext(scope.coroutineContext.minusKey(Job)) {
            mutations.withLock {
                require(target.owner == credentials.userId) { "Only owned albums support additions" }
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
        request: AlbumAdditionRequest,
    ): AlbumAdditionState = try {
        val location = when (target) {
            is AlbumTarget.Existing -> target.location
            is AlbumTarget.New -> remote.create(target.name.trim()).also { refreshCatalog() }
        }
        val address = AlbumAddress.of(location)
        if (address == null || address.owner != credentials.userId) {
            AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.NotWritable)
        } else {
            val queued = enqueue(request.uploads, UploadAlbum(address.owner, address.name))
            val result = remote.add(address, request.fileIds)
            applyAdded(location, result.presentIds, request.items)
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

    private fun applyAdded(
        location: AlbumLocation.Remote,
        ids: Set<Long>,
        supplied: List<MediaItem> = emptyList(),
    ) {
        if (ids.isEmpty()) return
        val entry = entries[location]
        if (entry == null) {
            refreshCatalog()
            return
        }
        entry.job?.cancel()
        entry.generation++
        val known = buildMap<Long, MediaItem> {
            library.state.value.values.forEach { item -> item.remoteFileId?.let { put(it, item) } }
            supplied.forEach { item -> item.remoteFileId?.let { put(it, item) } }
            entry.state.value.items.forEach { item -> item.remoteFileId?.let { put(it, item) } }
        }
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

    private fun entry(location: AlbumLocation): Entry = entries.getOrPut(location) {
        val blocked = location is AlbumLocation.Folder && permission.value != LocalMediaPermissionMode.Full
        Entry(AlbumContentsState(loading = !blocked, permissionRequired = blocked))
    }

    private fun load(location: AlbumLocation, entry: Entry) {
        if (location is AlbumLocation.Folder && permission.value != LocalMediaPermissionMode.Full) return
        entry.job?.cancel()
        val generation = ++entry.generation
        val hadItems = entry.loaded || entry.state.value.items.isNotEmpty()
        entry.state.value = entry.state.value.copy(loading = true, failed = false, permissionRequired = false)
        val stream = when (location) {
            is AlbumLocation.Remote -> remote.loadContents(location)
            is AlbumLocation.Folder -> local.loadContents(location)
        }
        entry.job = scope.launch {
            try {
                var latest: AlbumContentsBatch? = null
                stream.collect { batch ->
                    if (entry.generation != generation) return@collect
                    if (location is AlbumLocation.Remote) library.observeRemote(batch.items)
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
                    if (location is AlbumLocation.Remote) updateCatalogSummary(location, items)
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
                if (album.location != location) return@map album
                val cover = (album.cover as? AlbumCover.Remote)?.takeIf { existing ->
                    items.any { it.remoteFileId == existing.fileId }
                } ?: items.firstNotNullOfOrNull { item ->
                    item.remoteFileId?.let { AlbumCover.Remote(it, item.etag) }
                }
                album.copy(count = items.size, cover = cover)
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
                val cached = entries[location]?.takeIf { it.loaded && !it.stale } ?: return@map album
                val items = cached.state.value.items
                val cover = (album.cover as? AlbumCover.Remote)?.takeIf { existing ->
                    items.any { it.remoteFileId == existing.fileId }
                } ?: items.firstNotNullOfOrNull { item ->
                    item.remoteFileId?.let { AlbumCover.Remote(it, item.etag) }
                }
                album.copy(count = items.size, cover = cover)
            }
            mutableCatalog.update {
                it.copy(remote = AlbumSourceState(synced, supported = result.supported))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableCatalog.update { it.copy(remote = it.remote.copy(loading = false, failed = true)) }
        }
    }

    private suspend fun loadLocalCatalog() {
        mutableCatalog.update { it.copy(local = it.local.copy(loading = true, failed = false)) }
        try {
            val folders = local.loadCatalog()
            mutableCatalog.update { it.copy(local = AlbumSourceState(folders)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableCatalog.update { it.copy(local = it.local.copy(loading = false, failed = true)) }
        }
    }

    private fun clearFolders() {
        entries.forEach { (location, entry) ->
            if (location is AlbumLocation.Folder) {
                entry.job?.cancel()
                entry.generation++
                entry.loaded = false
                entry.stale = false
                entry.state.value = AlbumContentsState(permissionRequired = true)
            }
        }
    }

    private fun reloadEmptyFolders() {
        entries.forEach { (location, entry) ->
            if (location is AlbumLocation.Folder && !entry.loaded && entry.job?.isActive != true) {
                load(location, entry)
            }
        }
    }

    private fun refreshFolders() {
        entries.forEach { (location, entry) ->
            if (location is AlbumLocation.Folder) load(location, entry)
        }
    }
}

private class UploadEnqueueRejected : Exception()

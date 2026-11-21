package com.syrok0010.nextgallery.feature.albums

import androidx.annotation.MainThread
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.upload.EnqueueResult
import com.syrok0010.nextgallery.feature.upload.UploadAlbum
import com.syrok0010.nextgallery.feature.upload.UploadedFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns album reads and writes. Synchronous commands run on main, as does [scope]; upload workers
 * enter that dispatcher through [addUploaded]. The mutex serializes writes across suspension.
 */
@OptIn(FlowPreview::class)
internal class Albums(
    private val sessions: SessionStore,
    private val writer: MemoriesAlbumWriter,
    remover: MemoriesAlbumRemover,
    remoteSource: RemoteAlbumSource,
    localSource: LocalAlbumSource,
    source: AlbumContentsSource,
    library: MediaLibraryIndex,
    permission: StateFlow<LocalMediaPermissionMode?>,
    private val scope: CoroutineScope,
    private val enqueueUploads: suspend (Collection<MediaId>, UploadAlbum) -> EnqueueResult,
) : OperationStatusSource {
    private val mutations = Mutex()
    private val catalog =
        AlbumCatalogRepository(sessions.requireCredentials(), remoteSource, localSource, permission, scope)
    private val contents = AlbumContentsStore(sessions, source, library, permission, scope)
    private val additionState = MutableStateFlow<AlbumAdditionState>(AlbumAdditionState.Idle)
    private var additionJob: Job? = null
    private val removals = AlbumRemovals(sessions, remover, scope, mutations) { location, ids ->
        if (!contents.removed(location, ids)) catalog.refresh()
    }

    val state = combine(catalog.state, contents.snapshots, ::albumCatalogProjection)
        .stateIn(scope, SharingStarted.Eagerly, AlbumsUiState())
    val adding = additionState.map { it is AlbumAdditionState.Running }
        .stateIn(scope, SharingStarted.Eagerly, false)
    val removing = removals.progress.map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)
    override val operations = combine(
        additionState.map(::albumOperationSnapshots),
        removals.operations,
    ) { add, remove -> add + remove }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            permission.collectLatest { access ->
                if (access == LocalMediaPermissionMode.Full) {
                    localSource.changes().debounce(450).collect { contents.refreshFolders() }
                }
            }
        }
    }

    /** Reuses membership; reopening retries metadata still missing after a confirmed write. */
    @MainThread
    fun observeContents(location: AlbumLocation): StateFlow<AlbumContentsState> = contents.observe(location)

    /** Returns false if another addition is running or nobody is signed in; keep the selection then. */
    @MainThread
    fun add(target: AlbumTarget, request: AlbumAdditionRequest): Boolean {
        if (additionJob?.isActive == true) return false
        val credentials = (sessions.session.value as? SessionUiState.SignedIn)?.credentials ?: return false
        additionState.value = AlbumAdditionState.Running(target.name, request.fileIds.toSet())
        additionJob = scope.launch {
            mutations.withLock { additionState.value = performAddition(credentials, target, request) }
        }
        return true
    }

    private suspend fun performAddition(
        credentials: AccountCredentials,
        target: AlbumTarget,
        request: AlbumAdditionRequest,
    ): AlbumAdditionState = try {
        val location = when (target) {
            is AlbumTarget.Existing -> target.location
            is AlbumTarget.New -> writer.create(credentials, target.name.trim()).also { catalog.refresh() }
        }
        val address = AlbumAddress.of(location)
        if (address == null || address.owner != credentials.userId) {
            AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.NotWritable)
        } else {
            // Queue phone-only objects after creation and ownership validation, before any COPY.
            val queued = enqueue(request.uploads, UploadAlbum(address.owner, address.name))
            val result = writer.add(address, request.fileIds)
            added(location, result.presentIds, request.items)
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
        if (ids.isEmpty()) 0 else when (val result = enqueueUploads(ids, album)) {
            is EnqueueResult.Accepted -> result.count
            EnqueueResult.NothingToUpload -> 0
            EnqueueResult.Rejected -> throw UploadEnqueueRejected()
        }

    /** Accepts a new source snapshot, including removal of pending local confirmations. */
    @MainThread
    fun refresh() {
        catalog.refresh()
        contents.refresh()
    }

    /** Authoritative refresh of one album; automatic post-write reads preserve confirmations. */
    @MainThread
    fun refresh(location: AlbumLocation) = contents.refresh(location)

    fun removeMembers(location: AlbumLocation.Remote, name: String, ids: Set<Long>): Boolean =
        removals.remove(location, name, ids)

    private fun added(location: AlbumLocation.Remote, ids: Set<Long>, items: List<MediaItem> = emptyList()) {
        if (ids.isNotEmpty() && !contents.added(location, ids, items)) reconcile(location)
    }

    private fun reconcile(location: AlbumLocation.Remote) {
        contents.reconcile(location)
        catalog.refresh()
    }

    /** Worker entry point. Replay checks exact membership before COPY and never repeats the upload. */
    suspend fun addUploaded(
        account: AccountCredentials,
        target: UploadAlbum,
        file: UploadedFile,
        recovered: Boolean,
    ): Boolean = withContext(scope.coroutineContext.minusKey(Job)) {
        mutations.withLock {
            require(target.owner == account.userId) { "Only owned albums support additions" }
            val album = AlbumAddress(target.owner, target.name)
            val accepted = if (recovered) {
                writer.containsFile(file.path, album, file.fileId) ||
                    writer.addFile(file.path, album) == AlbumCopyOutcome.Added
            } else {
                writer.addFile(file.path, album) != AlbumCopyOutcome.Failed
            }
            if (accepted) {
                val location = AlbumLocation.Remote("${album.owner}/${album.name}")
                val id = file.fileId
                if (id == null) reconcile(location) else added(location, setOf(id))
            }
            accepted
        }
    }

    @MainThread
    override fun consumeSucceeded() {
        if ((additionState.value as? AlbumAdditionState.Done)?.result?.failed == 0) consumeAll()
        removals.consumeSucceeded()
    }

    @MainThread
    override fun consumeAll() {
        if (additionState.value !is AlbumAdditionState.Running) additionState.value = AlbumAdditionState.Idle
        removals.consumeAll()
    }
}

private class UploadEnqueueRejected : Exception()

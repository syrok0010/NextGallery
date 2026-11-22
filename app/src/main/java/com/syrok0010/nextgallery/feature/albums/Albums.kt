package com.syrok0010.nextgallery.feature.albums

import androidx.annotation.MainThread
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.upload.EnqueueResult
import com.syrok0010.nextgallery.feature.upload.UploadAlbum
import com.syrok0010.nextgallery.feature.upload.UploadedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(FlowPreview::class)
internal class Albums(
    private val credentials: AccountCredentials,
    private val writer: AlbumWriter,
    remoteSource: RemoteAlbumSource,
    localSource: LocalAlbumSource,
    source: AlbumContentsSource,
    library: MediaLibraryIndex,
    permission: StateFlow<LocalMediaPermissionMode?>,
    private val scope: CoroutineScope,
    private val enqueueUploads: suspend (Collection<MediaId>, UploadAlbum) -> EnqueueResult,
) : OperationStatusSource {
    private val mutations = Mutex()
    private lateinit var contents: AlbumContentsStore
    private val catalogRepository = AlbumCatalogRepository(
        credentials,
        remoteSource,
        localSource,
        permission,
        scope,
        onLocalChanged = { contents.refreshFolders() },
    )
    private val catalogStore get() = catalogRepository
    private val additionState = MutableStateFlow<AlbumAdditionState>(AlbumAdditionState.Idle)
    private var additionJob: Job? = null

    init {
        contents = AlbumContentsStore(credentials, source, library, permission, scope)
    }

    val catalog: StateFlow<AlbumsUiState> = combine(
        catalogStore.state,
        contents.snapshots,
        ::albumCatalogProjection,
    ).stateIn(scope, SharingStarted.Eagerly, AlbumsUiState())
    val state: StateFlow<AlbumsUiState> get() = catalog
    val adding: StateFlow<Boolean> = additionState.map { it is AlbumAdditionState.Running }
        .stateIn(scope, SharingStarted.Eagerly, false)
    override val operations: StateFlow<List<com.syrok0010.nextgallery.core.operation.OperationStatusSnapshot>> =
        additionState.map(::albumOperationSnapshots).stateIn(scope, SharingStarted.Eagerly, emptyList())

    @MainThread
    fun observeContents(location: AlbumLocation): kotlinx.coroutines.flow.Flow<AlbumContentsState> =
        contents.observe(location)

    @MainThread
    fun canModify(location: AlbumLocation): Boolean =
        location is AlbumLocation.Remote && AlbumAddress.of(location)?.owner == credentials.userId

    fun writableAlbums(state: AlbumsUiState): List<AlbumSummary> =
        writableAlbums(state.remote.items, credentials.userId)

    @MainThread
    fun add(target: AlbumTarget, request: AlbumAdditionRequest): Boolean {
        if (additionJob?.isActive == true) return false
        additionState.value = AlbumAdditionState.Running(target.name, request.fileIds.toSet())
        additionJob = scope.launch {
            mutations.withLock { additionState.value = performAddition(target, request) }
        }
        return true
    }

    private suspend fun performAddition(target: AlbumTarget, request: AlbumAdditionRequest): AlbumAdditionState = try {
        val location = when (target) {
            is AlbumTarget.Existing -> target.location
            is AlbumTarget.New -> writer.create(target.name.trim()).also { catalogRepository.refresh() }
        }
        val address = AlbumAddress.of(location)
        if (address == null || address.owner != credentials.userId) {
            AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.NotWritable)
        } else {
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

    @MainThread fun refreshCatalog() = catalogRepository.refresh()

    @MainThread fun refresh(location: AlbumLocation) = contents.refresh(location)

    private fun added(location: AlbumLocation.Remote, ids: Set<Long>, items: List<MediaItem> = emptyList()) {
        if (ids.isNotEmpty() && !contents.added(location, ids, items)) {
            contents.reconcile(location)
            catalogRepository.refresh()
        }
    }

    suspend fun addUploaded(target: UploadAlbum, file: UploadedFile, recovered: Boolean): Boolean =
        mutations.withLock {
            val album = AlbumAddress(target.owner, target.name)
            val accepted = if (recovered) {
                writer.containsFile(album, file.fileId) || writer.addFile(file.path, album) == AlbumCopyOutcome.Added
            } else {
                writer.addFile(file.path, album) != AlbumCopyOutcome.Failed
            }
            if (accepted) {
                val id = file.fileId
                if (id == null) catalogRepository.refresh()
                else added(album.toLocation(), setOf(id))
            }
            accepted
        }

    @MainThread override fun consumeSucceeded() {
        if ((additionState.value as? AlbumAdditionState.Done)?.result?.failed == 0) consumeAll()
    }

    @MainThread override fun consumeAll() {
        if (additionState.value !is AlbumAdditionState.Running) additionState.value = AlbumAdditionState.Idle
    }
}

private class UploadEnqueueRejected : Exception()

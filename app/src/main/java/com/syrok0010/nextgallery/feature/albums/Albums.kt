package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.localCopy
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

/** Owns known album membership and keeps every write, including upload follow-ups, consistent. */
@OptIn(FlowPreview::class)
internal class Albums(
    private val sessions: SessionStore,
    private val writer: MemoriesAlbumWriter,
    remoteSource: RemoteAlbumSource,
    localSource: LocalAlbumSource,
    source: AlbumContentsSource,
    private val library: MediaLibraryIndex,
    permission: StateFlow<LocalMediaPermissionMode?>,
    private val scope: CoroutineScope,
    private val enqueueUploads: suspend (Collection<MediaId>, UploadAlbum) -> EnqueueResult,
) : OperationStatusSource {
    private val mutations = Mutex()
    private val catalog =
        AlbumCatalogRepository(sessions, remoteSource, localSource, permission, scope)
    private val contents = AlbumContentsStore(sessions, source, library, permission, scope)
    private val additionState = MutableStateFlow<AlbumAdditionState>(AlbumAdditionState.Idle)
    private var additionJob: Job? = null

    val state = combine(catalog.state, contents.snapshots) { catalog, snapshots ->
        catalog.copy(
            remote = catalog.remote.copy(
                items = catalog.remote.items.map { album ->
                    val membership = snapshots[album.location] ?: return@map album
                    val members = membership.items
                    val cover = (album.cover as? AlbumCover.Remote)?.takeIf { cover ->
                        members.any {
                            it.remoteFileId == cover.fileId
                        }
                    }
                        ?: members
                            .firstOrNull {
                                it.remoteFileId != null
                            }?.let { AlbumCover.Remote(requireNotNull(it.remoteFileId), it.etag) }
                    album.copy(count = membership.total, cover = cover)
                },
            ),
        )
    }.stateIn(scope, SharingStarted.Eagerly, AlbumsUiState())

    val adding = additionState
        .map { it is AlbumAdditionState.Running }
        .stateIn(scope, SharingStarted.Eagerly, false)
    override val operations = additionState.map(::albumOperationSnapshots)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            permission.collectLatest { access ->
                if (access == LocalMediaPermissionMode.Full) {
                    localSource.changes().debounce(450).collect {
                        contents.refreshFolders()
                    }
                }
            }
        }
    }

    fun observeContents(location: AlbumLocation): StateFlow<AlbumContentsState> =
        contents.observe(location)
    /**
     * Returns false when the request is not accepted: another addition is running or nobody is
     * signed in. The caller keeps the selection in that case.
     *
     * Local-only objects are queued for upload once the album exists, so they never race album
     * creation; they are not uploaded when the album cannot be written.
     */
    fun add(target: AlbumTarget, request: AlbumAdditionRequest): Boolean {
        if (additionJob?.isActive == true) return false
        val credentials = (sessions.session.value as? SessionUiState.SignedIn)?.credentials ?: return false
        additionState.value = AlbumAdditionState.Running(target.name, request.fileIds.toSet())
        additionJob = scope.launch {
            mutations.withLock {
                additionState.value = try {
                    val location = when (target) {
                        is AlbumTarget.Existing -> target.location

                        is AlbumTarget.New ->
                            writer
                                .create(
                                    credentials,
                                    target.name.trim(),
                                ).also { catalog.refresh() }
                    }
                    val address = AlbumAddress.of(location)
                    if (address == null || address.owner != credentials.userId) {
                        AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.NotWritable)
                    } else {
                        val queued = if (request.uploads.isEmpty()) {
                            0
                        } else {
                            when (
                                val enqueue = enqueueUploads(
                                    request.uploads,
                                    UploadAlbum(address.owner, address.name),
                                )
                            ) {
                                is EnqueueResult.Accepted -> enqueue.count
                                EnqueueResult.NothingToUpload -> 0
                                EnqueueResult.Rejected -> throw UploadEnqueueRejected()
                            }
                        }
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
            }
        }
        return true
    }



    fun refresh() {
        catalog.refresh()
        contents.refresh()
    }
    fun refresh(location: AlbumLocation) = contents.refresh(location)

    private fun added(location: AlbumLocation.Remote, ids: Set<Long>, items: List<MediaItem>) {
        if (ids.isEmpty()) return
        if (!contents.added(location, ids, items)) {
            // Membership is confirmed, but unopened albums or unindexed files may lack metadata.
            contents.reconcile(location)
            catalog.refresh()
        }
    }

    /** Replay reconciles exact membership before COPY; it never repeats the upload itself. */
    suspend fun addUploaded(
        account: AccountCredentials,
        target: UploadAlbum,
        file: UploadedFile,
        recovered: Boolean,
    ): Boolean =
        mutations.withLock {
            require(target.owner == account.userId) { "Only owned albums support additions" }
            val album = AlbumAddress(target.owner, target.name)
            val result = if (recovered) {
                writer.containsFile(
                    file.path,
                    album,
                    file.fileId,
                ) ||
                    writer.addFile(file.path, album) == AlbumCopyOutcome.Added
            } else {
                writer.addFile(file.path, album) != AlbumCopyOutcome.Failed
            }
            if (result) {
                withContext(scope.coroutineContext.minusKey(Job)) {
                    val id = file.fileId
                    val known = library.state.value.values.firstOrNull {
                        it.localCopy?.contentUri == file.selection.identityUri
                    }
                    val item = if (known != null && id != null) {
                        known.copy(
                            assetRef = MediaAssetRef.LocalFirst(
                                requireNotNull(known.localCopy),
                                MediaAssetRef.MemoriesFile(id),
                            ),
                        )
                    } else {
                        null
                    }
                    if (id == null) {
                        contents.reconcile(AlbumLocation.Remote("${album.owner}/${album.name}"))
                        catalog.refresh()
                    } else {
                        added(
                            AlbumLocation.Remote("${album.owner}/${album.name}"),
                            setOf(id),
                            listOfNotNull(item),
                        )
                    }
                }
            }
            result
        }

    override fun consumeSucceeded() {
        if ((additionState.value as? AlbumAdditionState.Done)?.result?.failed == 0) consumeAll()
    }
    override fun consumeAll() {
        if (additionState.value !is AlbumAdditionState.Running) additionState.value = AlbumAdditionState.Idle
    }
}

private class UploadEnqueueRejected : Exception()

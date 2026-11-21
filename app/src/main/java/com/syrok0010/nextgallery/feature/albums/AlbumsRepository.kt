package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.AccountCredentials
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
internal class AlbumsRepository(
    sessions: SessionStore,
    private val writer: MemoriesAlbumWriter,
    remoteSource: RemoteAlbumSource,
    localSource: LocalAlbumSource,
    source: AlbumContentsSource,
    private val library: MediaLibraryIndex,
    permission: StateFlow<LocalMediaPermissionMode?>,
    private val scope: CoroutineScope,
    enqueueUploads: suspend (Collection<MediaId>, UploadAlbum) -> EnqueueResult,
) : OperationStatusSource {
    private val mutations = Mutex()
    private val catalog =
        AlbumCatalogRepository(sessions, remoteSource, localSource, permission, scope)
    private val contents = AlbumContentsStore(sessions, source, library, permission, scope)
    private val additions =
        AlbumAdditions(
            sessions,
            writer,
            enqueueUploads,
            scope,
            ::added,
            catalog::refresh,
            mutations,
        )

    val state = combine(catalog.state, contents.snapshots) { catalog, snapshots ->
        catalog.copy(
            remote = catalog.remote.copy(
                items = catalog.remote.items.map { album ->
                    val members = snapshots[album.location] ?: return@map album
                    val cover = (album.cover as? AlbumCover.Remote)?.takeIf { cover ->
                        members.any {
                            it.remoteFileId == cover.fileId
                        }
                    }
                        ?: members
                            .firstOrNull {
                                it.remoteFileId != null
                            }?.let { AlbumCover.Remote(requireNotNull(it.remoteFileId), it.etag) }
                    album.copy(count = members.size, cover = cover)
                },
            ),
        )
    }.stateIn(scope, SharingStarted.Eagerly, AlbumsUiState())

    val adding = additions.state
        .map { it is AlbumAdditionState.Running }
        .stateIn(scope, SharingStarted.Eagerly, false)
    override val operations = additions.operations

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
    fun add(target: AlbumTarget, request: AlbumAdditionRequest): Boolean =
        additions.add(target, request)

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
        additions.consumeSucceeded()
    }
    override fun consumeAll() {
        additions.consumeAll()
    }
}

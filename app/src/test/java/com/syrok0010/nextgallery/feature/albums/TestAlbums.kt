package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.upload.EnqueueResult
import com.syrok0010.nextgallery.feature.upload.UploadAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

internal fun NextcloudFixture.albums(
    scope: CoroutineScope,
    library: MediaLibraryIndex = MediaLibraryIndex(),
    permission: MutableStateFlow<LocalMediaPermissionMode?> =
        MutableStateFlow(LocalMediaPermissionMode.Full),
    catalog: suspend () -> RemoteAlbumsCatalog = { RemoteAlbumsCatalog(emptyList()) },
    contents: (AlbumLocation.Remote) -> Flow<AlbumContentsBatch> = {
        flowOf(AlbumContentsBatch(emptyList(), 0))
    },
    local: LocalFolders = object : LocalFolders {
        override suspend fun loadCatalog() = emptyList<AlbumSummary>()
        override fun loadContents(location: AlbumLocation.Folder) =
            flowOf(AlbumContentsBatch(emptyList(), 0))
        override fun changes() = emptyFlow<Unit>()
    },
    enqueue: suspend (
        Collection<MediaId>,
        UploadAlbum,
    ) -> EnqueueResult = { ids, _ -> EnqueueResult.Accepted(ids.size) },
): Albums {
    val delegate = writer
    val remote = object : RemoteAlbums by delegate {
        override suspend fun loadCatalog() = catalog()
        override fun loadContents(location: AlbumLocation.Remote) = contents(location)
    }
    return Albums(credentials, remote, local, library, permission, scope, enqueue)
}

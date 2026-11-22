package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.upload.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

internal fun NextcloudFixture.albums(
    scope: CoroutineScope,
    source: AlbumContentsSource =
        AlbumContentsSource { _ -> flowOf(AlbumContentsBatch(emptyList(), 0)) },
    library: MediaLibraryIndex = MediaLibraryIndex(),
    permission: MutableStateFlow<LocalMediaPermissionMode?> =
        MutableStateFlow(LocalMediaPermissionMode.Full),
    catalog: suspend () -> RemoteAlbums = { RemoteAlbums(emptyList()) },
    local: LocalAlbumSource = object : LocalAlbumSource {
        override suspend fun load() = emptyList<AlbumSummary>()
        override fun changes() = emptyFlow<Unit>()
    },
    enqueue: suspend (
        Collection<MediaId>,
        UploadAlbum,
    ) -> EnqueueResult = { ids, _ -> EnqueueResult.Accepted(ids.size) },
) = Albums(
    credentials, writer,
    object : RemoteAlbumSource {
        override suspend fun load() = catalog()
    },
    local, source, library, permission, scope, enqueue,
)

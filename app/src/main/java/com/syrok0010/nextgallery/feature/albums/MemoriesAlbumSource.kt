package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.memories.MemoriesAlbumDto
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials

internal class MemoriesAlbumSource(private val memories: MemoriesApiClient) : RemoteAlbumSource {
    override suspend fun load(credentials: AccountCredentials): RemoteAlbums {
        val config = memories.config()
        if (!config.albumsEnabled) return RemoteAlbums(emptyList(), supported = false)
        return RemoteAlbums(
            memories
                .albums()
                .map(::remoteAlbumSummary)
                .filter { config.showHiddenAlbums || !it.name.startsWith('.') }
                .distinctBy { it.id },
        )
    }
}

internal fun remoteAlbumSummary(album: MemoriesAlbumDto): AlbumSummary {
    val coverId = album.cover?.takeIf { it > 0 }
    val fileId = coverId ?: album.lastAddedPhoto?.takeIf { it > 0 }
    return AlbumSummary(
        id = "remote:${album.albumId}",
        name = album.name,
        origin = AlbumOrigin.Nextcloud,
        count = album.count?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt(),
        detail = album.userDisplay ?: album.user.orEmpty(),
        location = (album.clusterId ?: album.user?.let { "$it/${album.name}" })
            ?.let(AlbumLocation::Remote),
        cover = fileId?.let {
            AlbumCover.Remote(
                it,
                if (coverId != null) album.coverEtag else album.lastAddedPhotoEtag,
            )
        },
    )
}

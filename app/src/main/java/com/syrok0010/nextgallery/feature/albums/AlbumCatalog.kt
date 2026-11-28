package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.localCopy
import java.util.Locale
import kotlinx.serialization.Serializable

internal enum class AlbumOrigin { Nextcloud, Phone }

/** Source address, independent of display labels and catalog ordering. */
@Serializable
internal sealed interface AlbumLocation {
    @Serializable data class Remote(val clusterId: String) : AlbumLocation

    @Serializable data class Folder(val volume: String, val path: String) : AlbumLocation
}

/** Owner and collection name of a Photos album addressed by Memories `cluster_id`. */
internal data class AlbumAddress(val owner: String, val name: String) {
    fun toLocation(): AlbumLocation.Remote = AlbumLocation.Remote("$owner/$name")

    companion object {
        fun of(location: AlbumLocation.Remote): AlbumAddress? {
            val owner = location.clusterId.substringBefore('/', "")
            val name = location.clusterId.substringAfter('/', "")
            return if (owner.isEmpty() || name.isEmpty()) null else AlbumAddress(owner, name)
        }
    }
}

internal data class AlbumSummary(
    val id: String,
    val name: String,
    val origin: AlbumOrigin,
    val count: Int?,
    val detail: String,
    val cover: AlbumCover? = null,
    val location: AlbumLocation? = null,
)

internal sealed interface AlbumCover {
    data class Remote(val fileId: Long, val etag: String?) : AlbumCover
    data class Local(val uri: String, val modified: Long) : AlbumCover
}

internal data class RemoteAlbumsCatalog(
    val albums: List<AlbumSummary>,
    val supported: Boolean = true,
)

internal data class AlbumSourceState(
    val items: List<AlbumSummary> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val supported: Boolean = true,
)

internal data class AlbumsUiState(
    val remote: AlbumSourceState = AlbumSourceState(),
    val local: AlbumSourceState = AlbumSourceState(),
    val localAccess: Boolean = false,
) {
    val items: List<AlbumSummary> get() = albumCatalog(remote.items, local.items)
}

internal data class AlbumContentsBatch(
    val items: List<MediaItem>,
    val total: Int,
    val loaded: Int = items.size,
)

internal data class AlbumContentsState(
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val loaded: Int = 0,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val permissionRequired: Boolean = false,
)

/** Containers are never reconciled through media identity or display names. */
internal fun albumCatalog(
    remote: List<AlbumSummary>,
    local: List<AlbumSummary>,
): List<AlbumSummary> =
    (remote + local).sortedWith(
        compareBy<AlbumSummary> {
            it.name.lowercase(Locale.ROOT)
        }.thenBy { it.id },
    )

/** Groups indexed local copies into device folders without a second MediaStore scan. */
internal fun localAlbumSummaries(items: Collection<MediaItem>): List<AlbumSummary> =
    items
        .mapNotNull { item ->
            val local = item.localCopy ?: return@mapNotNull null
            val volume = local.volumeName ?: return@mapNotNull null
            val path = local.relativePath ?: return@mapNotNull null
            (volume to path) to item
        }.groupBy({ it.first }, { it.second })
        .map { (key, members) ->
            val (volume, path) = key
            val cover = requireNotNull(members.maxWith(localCoverOrder).localCopy)
            AlbumSummary(
                id = "local:${volume.length}:$volume:$path",
                name = path.trimEnd('/').substringAfterLast('/').ifEmpty { volume },
                origin = AlbumOrigin.Phone,
                count = members.size,
                detail = localFolderDetail(volume, path),
                cover = AlbumCover.Local(cover.contentUri, cover.modifiedAtEpochSeconds ?: 0L),
                location = AlbumLocation.Folder(volume, path),
            )
        }

internal fun folderItems(
    items: Collection<MediaItem>,
    location: AlbumLocation.Folder,
): List<MediaItem> =
    items
        .filter { item ->
            val local = item.localCopy
            local != null && local.volumeName == location.volume && local.relativePath == location.path
        }.sortedWith(albumItemOrder)

internal val albumItemOrder: Comparator<MediaItem> =
    compareByDescending<MediaItem> { it.dayId }
        .thenByDescending { it.takenAtEpochSeconds }
        .thenBy { it.mediaId.value }

private const val PRIMARY_VOLUME = "external_primary"

private fun localFolderDetail(volume: String, path: String): String {
    val cleanPath = path.trimEnd('/')
    return when {
        cleanPath.isEmpty() -> volume
        volume == PRIMARY_VOLUME -> cleanPath
        else -> "$cleanPath · $volume"
    }
}

private val localCoverOrder: Comparator<MediaItem> =
    compareBy<MediaItem> {
        it.localCopy?.modifiedAtEpochSeconds ?: it.takenAtEpochSeconds ?: Long.MIN_VALUE
    }.thenBy { it.localCopy?.contentUri.orEmpty() }

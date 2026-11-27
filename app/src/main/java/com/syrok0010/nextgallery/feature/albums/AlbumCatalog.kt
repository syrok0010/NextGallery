package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
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
    val permission: LocalMediaPermissionMode? = null,
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

internal data class LocalAlbumEntry(
    val volume: String,
    val path: String,
    val uri: String,
    val modified: Long,
)

internal fun localAlbumSummaries(entries: Sequence<LocalAlbumEntry>): List<AlbumSummary> {
    val groups = linkedMapOf<Pair<String, String>, AlbumSummary>()
    entries.forEach { entry ->
        val key = entry.volume to entry.path
        val previous = groups[key]
        groups[key] = previous?.copy(count = requireNotNull(previous.count) + 1)
            ?: AlbumSummary(
                id = "local:${entry.volume.length}:${entry.volume}:${entry.path}",
                name = entry.path.trimEnd('/').substringAfterLast('/').ifEmpty { entry.volume },
                origin = AlbumOrigin.Phone,
                count = 1,
                detail = "${entry.path} · ${entry.volume}",
                cover = AlbumCover.Local(entry.uri, entry.modified),
                location = AlbumLocation.Folder(entry.volume, entry.path),
            )
    }
    return groups.values.toList()
}

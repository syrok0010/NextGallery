package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.util.Locale
import kotlinx.coroutines.flow.Flow

internal enum class AlbumOrigin { Nextcloud, Phone }
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
internal data class RemoteAlbums(val albums: List<AlbumSummary>, val supported: Boolean = true)
internal interface RemoteAlbumSource {
    suspend fun load(credentials: AccountCredentials): RemoteAlbums
}
internal interface LocalAlbumSource {
    suspend fun load(): List<AlbumSummary>
    fun changes(): Flow<Unit>
}

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
        groups[key] = if (previous == null) {
            AlbumSummary(
                id = "local:${entry.volume.length}:${entry.volume}:${entry.path}",
                name = entry.path.trimEnd('/').substringAfterLast('/').ifEmpty { entry.volume },
                origin = AlbumOrigin.Phone,
                count = 1,
                detail = "${entry.path} · ${entry.volume}",
                cover = AlbumCover.Local(entry.uri, entry.modified),
                location = AlbumLocation.Folder(entry.volume, entry.path),
            )
        } else {
            previous.copy(count = requireNotNull(previous.count) + 1)
        }
    }
    return groups.values.toList()
}

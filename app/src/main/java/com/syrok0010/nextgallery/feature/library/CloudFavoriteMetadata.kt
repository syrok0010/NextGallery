package com.syrok0010.nextgallery.feature.library

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.network.bestEffort
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class FavoriteRead(val generation: Long, val revision: Long)
internal data class FavoriteValue(val revision: Long, val favorite: Boolean)

/** Serializes confirmed writes with cache ingestion. A read begun before a write cannot undo it. */
internal class CloudFavoriteMetadata(private val sessions: SessionStore) {
    private val mutex = Mutex()
    private var owner = -1L
    private var revision = 0L
    private val mutableValues = MutableStateFlow<Map<Long, FavoriteValue>>(emptyMap())
    val values = mutableValues.asStateFlow()

    fun begin(credentials: AccountCredentials): FavoriteRead {
        check((sessions.session.value as? SessionUiState.SignedIn)?.credentials == credentials)
        if (owner != sessions.generation) {
            owner = sessions.generation
            revision = 0
            mutableValues.value = emptyMap()
        }
        return FavoriteRead(owner, revision)
    }

    fun isCurrent(read: FavoriteRead): Boolean =
        read.generation == sessions.generation &&
            sessions.session.value is SessionUiState.SignedIn

    suspend fun ingest(
        read: FavoriteRead,
        items: List<MediaItem>,
        persist: suspend (List<MediaItem>) -> Unit = {},
    ): List<MediaItem> =
        mutex.withLock {
            ensureCurrent(read)
            val previous = mutableValues.value
            val corrected = items.map { item ->
                val value = previous[item.remoteFileId]
                if (value != null && value.revision > read.revision) {
                    item.copy(
                        isFavorite = value.favorite,
                    )
                } else {
                    item
                }
            }
            // A cache failure must not discard successful server metadata. Cancellation still wins.
            bestEffort { persist(corrected) }
            ensureCurrent(read)
            // Retain a confirmed value for already-published snapshots, but allow a later server read
            // to observe changes made in another client. Only writes advance the revision.
            mutableValues.value = previous.toMutableMap().apply {
                corrected.forEach { item ->
                    item.remoteFileId?.let { id ->
                        previous[id]?.takeIf { it.revision <= read.revision }?.let {
                            put(id, it.copy(favorite = item.isFavorite))
                        }
                    }
                }
            }
            corrected
        }

    suspend fun confirm(
        read: FavoriteRead,
        item: MediaItem,
        favorite: Boolean,
        persist: suspend (MediaItem) -> Unit,
    ) = mutex.withLock {
        ensureCurrent(read)
        persist(item.copy(isFavorite = favorite))
        ensureCurrent(read)
        mutableValues.value = mutableValues.value +
            (requireNotNull(item.remoteFileId) to FavoriteValue(++revision, favorite))
    }

    // Only a read begun before the write needs protection from whole-day invalidation. A newer
    // authoritative index must be allowed to evict metadata, including a deleted day's last photo.
    fun fileIdsNewerThan(read: FavoriteRead): Set<Long> {
        ensureCurrent(read)
        return mutableValues.value.filterValues { it.revision > read.revision }.keys
    }

    suspend fun clear(persist: suspend () -> Unit) =
        mutex.withLock {
            persist()
            mutableValues.value = emptyMap()
        }

    private fun ensureCurrent(read: FavoriteRead) {
        if (!isCurrent(read)) throw CancellationException("Expired cloud metadata session")
    }
}

internal fun favoriteProjection(
    items: List<MediaItem>,
    values: Map<Long, FavoriteValue>,
): List<MediaItem> =
    items.map { item ->
        val favorite = values[item.remoteFileId]?.favorite ?: item.isFavorite
        item.copy(isFavorite = item.remoteFileId != null && favorite)
    }

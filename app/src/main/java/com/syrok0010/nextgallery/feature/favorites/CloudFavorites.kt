package com.syrok0010.nextgallery.feature.favorites

import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.remoteCopy
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.library.CloudFavoriteMetadata
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.favoriteProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class FavoritePending(val fileId: Long, val previous: Boolean)
internal data class FavoritesState(
    val items: List<MediaItem> = emptyList(),
    val departed: Set<MediaId> = emptySet(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val readRevision: Long = -1,
)

/** Application/session-owned operation. Pending retains the old value; no offline queue. */
internal class CloudFavorites(
    private val sessions: SessionStore,
    private val metadata: CloudFavoriteMetadata,
    private val load: suspend (AccountCredentials) -> List<MediaItem>,
    private val library: MediaLibraryIndex,
    private val scope: CoroutineScope,
    private val write: suspend (AccountCredentials, Long, Boolean) -> Unit,
    private val persist: suspend (MediaItem) -> Unit,
) {
    private val pendingState = MutableStateFlow<FavoritePending?>(null)
    val pending = pendingState.asStateFlow()
    private val errorState = MutableStateFlow(false)
    val error = errorState.asStateFlow()
    private val contents = MutableStateFlow(FavoritesState())
    private val changedItems = MutableStateFlow<Map<Long, MediaItem>>(emptyMap())
    val state = combine(contents, library.state, metadata.values, changedItems) { contents, known, values, changed ->
        // Query membership and the property are different: an album file outside timeline paths
        // can still have a true server favorite. Only newer local writes augment a complete query.
        val recentChanges = changed.filter { (id, _) ->
            !contents.loaded || (values[id]?.revision ?: -1) > contents.readRevision
        }.values
        val cached = if (contents.loaded) emptyList() else known.values.filter { it.remoteFileId != null && it.isFavorite }
        val candidates = (recentChanges + contents.items + cached).distinctBy { it.mediaId }
        val projected = favoriteProjection(candidates, values)
        val items = projected.filter { it.isFavorite && it.remoteFileId != null }.map { item ->
            val canonical = known[item.mediaId]
            if (canonical != null && canonical.remoteFileId == item.remoteFileId) item.copy(assetRef = canonical.assetRef) else item
        }.sortedWith(compareByDescending<MediaItem> { it.takenAtEpochSeconds }.thenBy { it.mediaId.value })
        contents.copy(items = items, departed = (contents.departed + projected.filterNot { it.isFavorite }.map { it.mediaId }) - items.map { it.mediaId })
    }.stateIn(scope, SharingStarted.Eagerly, FavoritesState())
    private var writeJob: Job? = null
    private var loadJob: Job? = null

    init {
        scope.launch {
            sessions.generations.collectLatest {
                val session = sessions.session.value
                writeJob?.cancel()
                loadJob?.cancel()
                pendingState.value = null
                errorState.value = false
                contents.value = FavoritesState()
                changedItems.value = emptyMap()
                if (session is SessionUiState.SignedIn) metadata.begin(session.credentials)
            }
        }
    }

    fun set(item: MediaItem): Boolean {
        if (pendingState.value != null) return false
        val credentials = (sessions.session.value as? SessionUiState.SignedIn)?.credentials ?: return false
        val id = item.remoteFileId ?: return false
        val read = metadata.begin(credentials)
        val current = favoriteProjection(listOf(item), metadata.values.value).single()
        pendingState.value = FavoritePending(id, current.isFavorite)
        errorState.value = false
        writeJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                write(credentials, id, !current.isFavorite)
                if (!metadata.isCurrent(read)) return@launch
                metadata.confirm(read, current.copy(assetRef = requireNotNull(current.remoteCopy)), !current.isFavorite, persist)
                if (metadata.isCurrent(read)) {
                    // Include album-only items as well, without refreshing the entire timeline.
                    changedItems.value = changedItems.value + (id to current)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (metadata.isCurrent(read)) errorState.value = true
            } finally {
                if (metadata.isCurrent(read)) pendingState.value = null
            }
        }
        writeJob?.start()
        return true
    }

    fun refresh() {
        val credentials = (sessions.session.value as? SessionUiState.SignedIn)?.credentials ?: return
        val read = metadata.begin(credentials)
        loadJob?.cancel()
        contents.value = contents.value.copy(loading = true, failed = false)
        loadJob = scope.launch {
            try {
                val items = load(credentials)
                if (metadata.isCurrent(read)) {
                    val departed = state.value.items.mapTo(mutableSetOf()) { it.mediaId } - items.map { it.mediaId }.toSet()
                    contents.value = FavoritesState(items, departed = departed, loaded = true, readRevision = read.revision)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (metadata.isCurrent(read)) contents.value = contents.value.copy(loading = false, failed = true)
            }
        }
    }

    fun consumeError() { errorState.value = false }
}

package com.syrok0010.nextgallery.feature.albums

import androidx.annotation.MainThread
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class AlbumContentsState(
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val loaded: Int = 0,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val permissionRequired: Boolean = false,
    /** Confirmed members whose metadata has not reached Memories or the shared index yet. */
    val pendingMetadata: Int = 0,
    val departedIds: Set<MediaId> = emptySet(),
)

/** Read lifecycle and publication, confined to the scope's main dispatcher. */
@MainThread
internal class AlbumContentsStore(
    private val sessions: SessionStore,
    private val source: AlbumContentsSource,
    private val library: MediaLibraryIndex,
    private val permission: StateFlow<LocalMediaPermissionMode?>,
    private val scope: CoroutineScope,
) {
    private val entries = mutableMapOf<AlbumLocation, Entry>()
    private val complete = MutableStateFlow<Map<AlbumLocation, AlbumContentsState>>(emptyMap())
    val snapshots = complete.asStateFlow()

    private class Entry(initial: AlbumContentsState) {
        var membership = AlbumMembership()
        val state = MutableStateFlow(initial)
        lateinit var projected: StateFlow<AlbumContentsState>
        var job: Job? = null
        var request = 0L
    }

    init {
        scope.launch {
            combine(sessions.session, permission) { session, access -> session to access }
                .collect { (session, access) ->
                    entries.forEach { (location, entry) ->
                        if (session !is SessionUiState.SignedIn ||
                            (location is AlbumLocation.Folder && access != LocalMediaPermissionMode.Full)
                        ) {
                            entry.job?.cancel()
                            entry.request++
                            entry.membership = AlbumMembership()
                            entry.state.value = AlbumContentsState(permissionRequired = session is SessionUiState.SignedIn && location is AlbumLocation.Folder)
                            complete.value -= location
                        } else if (!entry.membership.initialized && entry.job?.isActive != true) {
                            load(location, entry, preserveConfirmed = true)
                        }
                    }
                }
        }
        scope.launch {
            library.state.collect { known ->
                val metadata = known.values.associateBy { it.remoteFileId }
                entries.forEach { (location, entry) ->
                    entry.membership = entry.membership.resolve(metadata)
                    publish(location, entry)
                }
            }
        }
    }

    private fun entry(location: AlbumLocation): Entry = entries.getOrPut(location) {
        val blocked = location is AlbumLocation.Folder && permission.value != LocalMediaPermissionMode.Full
        Entry(AlbumContentsState(loading = !blocked, permissionRequired = blocked)).also { entry ->
            entry.projected = combine(entry.state, library.state, permission) { state, known, access ->
                if (location is AlbumLocation.Folder && access != LocalMediaPermissionMode.Full) {
                    AlbumContentsState(permissionRequired = true)
                } else {
                    state.copy(items = albumMediaProjection(state.items, known, access == LocalMediaPermissionMode.Full))
                }
            }.stateIn(scope, SharingStarted.Eagerly, entry.state.value)
        }
    }

    fun observe(location: AlbumLocation): StateFlow<AlbumContentsState> {
        val entry = entry(location)
        if ((!entry.membership.initialized || entry.state.value.pendingMetadata > 0) && entry.job?.isActive != true) {
            load(location, entry, preserveConfirmed = true)
        }
        return entry.projected
    }

    fun refresh(location: AlbumLocation? = null) {
        entries.forEach { (key, entry) -> if (location == null || key == location) load(key, entry) }
    }

    fun reconcile(location: AlbumLocation) {
        entries[location]?.let { load(location, it, preserveConfirmed = true) }
    }

    fun refreshFolders() {
        entries.forEach { (location, entry) -> if (location is AlbumLocation.Folder) load(location, entry) }
    }

    fun added(location: AlbumLocation.Remote, ids: Set<Long>, supplied: List<MediaItem>): Boolean {
        val entry = entry(location)
        val metadata = library.state.value.values.associateBy { it.remoteFileId }
        entry.membership = entry.membership.added(ids, supplied).resolve(metadata)
        publish(location, entry)
        return entry.membership.initialized && entry.state.value.pendingMetadata == 0
    }

    fun removed(location: AlbumLocation.Remote, ids: Set<Long>): Boolean {
        val entry = entries[location] ?: return false
        val departed = entry.state.value.items
            .filter { it.remoteFileId in ids }
            .mapTo(mutableSetOf()) { it.mediaId }
        entry.membership = entry.membership.removed(ids)
        entry.state.value = entry.state.value.copy(departedIds = entry.state.value.departedIds + departed)
        publish(location, entry)
        return entry.membership.initialized
    }

    private fun load(location: AlbumLocation, entry: Entry, preserveConfirmed: Boolean = false) {
        val credentials = (sessions.session.value as? SessionUiState.SignedIn)?.credentials ?: return
        if (location is AlbumLocation.Folder && permission.value != LocalMediaPermissionMode.Full) return
        entry.job?.cancel()
        val request = ++entry.request
        if (!preserveConfirmed) entry.membership = entry.membership.authoritative()
        val hadSnapshot = entry.membership.initialized || entry.membership.snapshot.items.isNotEmpty()
        // Retain the last visible snapshot until a complete replacement succeeds.
        entry.state.value = entry.state.value.copy(loading = true, failed = false, permissionRequired = false)
        entry.job = scope.launch {
            try {
                var latest = AlbumContentsBatch(emptyList(), 0)
                source.load(location, credentials).collect { batch ->
                    if (entry.request != request) return@collect
                    latest = batch
                    if (!hadSnapshot) {
                        entry.membership = entry.membership.read(batch, finished = false)
                        publish(location, entry)
                    }
                }
                if (entry.request == request) {
                    check(latest.loaded == latest.total) { "Incomplete album membership" }
                    val previousIds = entry.state.value.items.mapTo(mutableSetOf()) { it.mediaId }
                    entry.membership = entry.membership.read(latest, finished = true)
                    val currentIds = entry.membership.contents().items.mapTo(mutableSetOf()) { it.mediaId }
                    entry.state.value = entry.state.value.copy(
                        loading = false,
                        departedIds = entry.state.value.departedIds + (previousIds - currentIds),
                    )
                    publish(location, entry)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (entry.request == request) entry.state.value = entry.state.value.copy(loading = false, failed = true)
            }
        }
    }

    private fun publish(location: AlbumLocation, entry: Entry) {
        val previous = entry.state.value
        val state = entry.membership.contents().copy(
            loading = previous.loading,
            failed = previous.failed,
            permissionRequired = previous.permissionRequired,
            departedIds = previous.departedIds - entry.membership.contents().items.map { it.mediaId }.toSet(),
        )
        entry.state.value = state
        if (entry.membership.initialized) complete.value += location to state
    }
}

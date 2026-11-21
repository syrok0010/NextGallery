package com.syrok0010.nextgallery.feature.albums

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
)

/** In-memory membership, independent of how many confirmed members have renderable metadata. */
internal class AlbumContentsStore(
    private val sessions: SessionStore,
    private val source: AlbumContentsSource,
    private val library: MediaLibraryIndex,
    private val permission: StateFlow<LocalMediaPermissionMode?>,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val entries = mutableMapOf<AlbumLocation, Entry>()
    private val complete = MutableStateFlow<Map<AlbumLocation, AlbumContentsState>>(emptyMap())
    val snapshots = complete.asStateFlow()

    private class Entry {
        val state = MutableStateFlow(AlbumContentsState())
        lateinit var projected: StateFlow<AlbumContentsState>
        var job: Job? = null
        var request = 0L
        var initialized = false
        // Every key is confirmed membership; a null value means metadata is still unknown.
        // Automatic reads preserve these facts. An explicit refresh is authoritative again.
        val confirmed = mutableMapOf<Long, MediaItem?>()
    }

    init {
        scope.launch {
            combine(sessions.session, permission) { session, access -> session to access }
                .collect { (session, access) ->
                    synchronized(lock) {
                        entries.forEach { (location, entry) ->
                            if (session !is SessionUiState.SignedIn ||
                                (location is AlbumLocation.Folder && access != LocalMediaPermissionMode.Full)
                            ) {
                                entry.job?.cancel()
                                entry.request++
                                entry.initialized = false
                                entry.confirmed.clear()
                                entry.state.value = AlbumContentsState(permissionRequired = session is SessionUiState.SignedIn && location is AlbumLocation.Folder)
                                complete.update { it - location }
                            } else if (!entry.initialized && entry.job?.isActive != true) {
                                load(location, entry, preserveConfirmed = true)
                            }
                        }
                    }
                }
        }
        scope.launch {
            library.state.collect { known ->
                synchronized(lock) {
                    val byFileId = known.values.associateBy { it.remoteFileId }
                    entries.forEach { (location, entry) ->
                        val resolved = entry.confirmed.filterValues { it == null }.keys.mapNotNull { id ->
                            byFileId[id]?.let { id to it }
                        }.toMap()
                        if (resolved.isNotEmpty()) {
                            entry.confirmed.putAll(resolved)
                            publish(location, entry, entry.state.value)
                        }
                    }
                }
            }
        }
    }

    private fun entry(location: AlbumLocation): Entry = entries.getOrPut(location) {
        Entry().also { entry ->
            entry.projected = combine(entry.state, library.state, permission) { state, known, access ->
                if (location is AlbumLocation.Folder && access != LocalMediaPermissionMode.Full) {
                    AlbumContentsState(permissionRequired = true)
                } else {
                    state.copy(items = albumMediaProjection(state.items, known, access == LocalMediaPermissionMode.Full))
                }
            }.stateIn(scope, SharingStarted.Eagerly, entry.state.value)
        }
    }

    fun observe(location: AlbumLocation): StateFlow<AlbumContentsState> = synchronized(lock) {
        val entry = entry(location)
        if ((!entry.initialized || entry.state.value.pendingMetadata > 0) && entry.job?.isActive != true) {
            load(location, entry, preserveConfirmed = true)
        }
        entry.projected
    }

    fun refresh(location: AlbumLocation? = null) = synchronized(lock) {
        entries.forEach { (key, entry) -> if (location == null || key == location) load(key, entry) }
    }

    fun reconcile(location: AlbumLocation) = synchronized(lock) {
        entries[location]?.let { load(location, it, preserveConfirmed = true) }
    }

    fun refreshFolders() = synchronized(lock) {
        entries.forEach { (location, entry) -> if (location is AlbumLocation.Folder) load(location, entry) }
    }

    /** Membership is retained even when none of the confirmed file IDs has metadata yet. */
    fun added(location: AlbumLocation.Remote, ids: Set<Long>, supplied: List<MediaItem>): Boolean = synchronized(lock) {
        val entry = entry(location)
        val known = (supplied + library.state.value.values + entry.state.value.items).associateBy { it.remoteFileId }
        ids.forEach { id -> entry.confirmed[id] = known[id] ?: entry.confirmed[id] }
        publish(location, entry, entry.state.value)
        entry.initialized && entry.state.value.pendingMetadata == 0
    }

    private fun load(location: AlbumLocation, entry: Entry, preserveConfirmed: Boolean = false) {
        val credentials = (sessions.session.value as? SessionUiState.SignedIn)?.credentials ?: return
        if (location is AlbumLocation.Folder && permission.value != LocalMediaPermissionMode.Full) return
        entry.job?.cancel()
        val request = ++entry.request
        if (!preserveConfirmed) entry.confirmed.clear()
        val hadSnapshot = entry.initialized || entry.state.value.items.isNotEmpty()
        publish(location, entry, entry.state.value.copy(loading = true, failed = false, permissionRequired = false))
        entry.job = scope.launch {
            try {
                var latest = AlbumContentsBatch(emptyList(), 0)
                source.load(location, credentials).collect { batch ->
                    synchronized(lock) {
                        if (entry.request != request) return@synchronized
                        latest = batch
                        if (!hadSnapshot) commitRead(location, entry, batch, finished = false)
                    }
                }
                synchronized(lock) {
                    if (entry.request == request) {
                        check(latest.loaded == latest.total) { "Incomplete album membership" }
                        entry.initialized = true
                        commitRead(location, entry, latest, finished = true)
                        val reportedIds = latest.items.mapTo(mutableSetOf()) { it.remoteFileId }
                        entry.confirmed.keys.removeAll(reportedIds)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (entry.request == request) entry.state.update { it.copy(loading = false, failed = true) }
                }
            }
        }
    }

    private fun commitRead(location: AlbumLocation, entry: Entry, batch: AlbumContentsBatch, finished: Boolean) {
        publish(location, entry, entry.state.value.copy(items = batch.items,
            total = batch.total, loaded = batch.loaded, loading = !finished,
        ))
    }

    private fun publish(location: AlbumLocation, entry: Entry, next: AlbumContentsState) {
        val items = (next.items.filterNot { entry.confirmed[it.remoteFileId] != null } +
            entry.confirmed.values.filterNotNull()).distinctBy { it.mediaId }
        val materialized = items.mapTo(mutableSetOf()) { it.remoteFileId }
        val pending = entry.confirmed.keys.count { it !in materialized }
        val total = if (entry.initialized) items.size + pending else maxOf(next.total, items.size + pending)
        val state = next.copy(items = items, loaded = items.size, total = total, pendingMetadata = pending)
        entry.state.value = state
        if (entry.initialized) complete.update { it + (location to state) }
    }
}

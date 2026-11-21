package com.syrok0010.nextgallery.feature.albums

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
    val departedIds: Set<MediaId> = emptySet(),
)

/** The in-memory membership of known albums. Only AlbumsRepository coordinates writes here. */
internal class AlbumContentsStore(
    private val sessions: SessionStore,
    private val source: AlbumContentsSource,
    private val library: MediaLibraryIndex,
    private val permission: StateFlow<LocalMediaPermissionMode?>,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val entries = mutableMapOf<AlbumLocation, Entry>()
    private val complete = MutableStateFlow<Map<AlbumLocation, List<MediaItem>>>(emptyMap())
    val snapshots = complete.asStateFlow()

    private class Entry {
        val state = MutableStateFlow(AlbumContentsState())
        lateinit var projected: StateFlow<AlbumContentsState>
        var job: Job? = null
        var request = 0L
        var initialized = false
        // Automatic reads retain confirmed edits until the server reports them.
        // An explicit refresh starts a new authoritative read.
        val edits = mutableMapOf<Long, MediaItem?>()
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
                                entry.edits.clear()
                                entry.state.value = AlbumContentsState(permissionRequired = session is SessionUiState.SignedIn && location is AlbumLocation.Folder)
                                complete.update { it - location }
                            } else if (!entry.initialized && entry.job?.isActive != true) {
                                load(location, entry)
                            }
                        }
                    }
                }
        }
    }

    fun observe(location: AlbumLocation): StateFlow<AlbumContentsState> = synchronized(lock) {
        entries.getOrPut(location) {
            Entry().also { entry ->
                entry.projected = combine(entry.state, library.state, permission) { state, known, access ->
                    if (location is AlbumLocation.Folder && access != LocalMediaPermissionMode.Full) {
                        AlbumContentsState(permissionRequired = true)
                    } else {
                        state.copy(items = albumMediaProjection(state.items, known, access == LocalMediaPermissionMode.Full))
                    }
                }.stateIn(scope, SharingStarted.Eagerly, entry.state.value)
                load(location, entry)
            }
        }.projected
    }

    fun refresh(location: AlbumLocation? = null) = synchronized(lock) {
        entries.forEach { (key, entry) -> if (location == null || key == location) load(key, entry) }
    }

    fun reconcile(location: AlbumLocation) = synchronized(lock) {
        entries[location]?.let { load(location, it, preserveEdits = true) }
    }

    fun refreshFolders() = synchronized(lock) {
        entries.forEach { (location, entry) -> if (location is AlbumLocation.Folder) load(location, entry) }
    }

    /** Returns false when some confirmed additions lack metadata and need a targeted read. */
    fun added(location: AlbumLocation.Remote, ids: Set<Long>, supplied: List<MediaItem>): Boolean = synchronized(lock) {
        val entry = entries[location] ?: return@synchronized false
        val known = (supplied + library.state.value.values + entry.state.value.items).associateBy { it.remoteFileId }
        val items = ids.mapNotNull(known::get)
        edit(location, entry, items.associate { requireNotNull(it.remoteFileId) to it })
        items.size == ids.size
    }

    fun removed(location: AlbumLocation.Remote, ids: Set<Long>): Boolean = synchronized(lock) {
        val entry = entries[location] ?: return@synchronized false
        edit(location, entry, ids.associateWith { null })
        entry.initialized
    }

    private fun edit(location: AlbumLocation, entry: Entry, changes: Map<Long, MediaItem?>) {
        if (changes.isEmpty()) return
        entry.edits.putAll(changes)
        val current = entry.state.value
        val next = applyEdits(current.items, changes)
        publish(location, entry, current.copy(items = next, loaded = next.size,
            total = if (entry.initialized) next.size else current.total,
        ))
    }

    private fun load(location: AlbumLocation, entry: Entry, preserveEdits: Boolean = false) {
        val credentials = (sessions.session.value as? SessionUiState.SignedIn)?.credentials ?: return
        if (location is AlbumLocation.Folder && permission.value != LocalMediaPermissionMode.Full) return
        entry.job?.cancel()
        val request = ++entry.request
        if (!preserveEdits) entry.edits.clear()
        val hadSnapshot = entry.initialized || entry.state.value.items.isNotEmpty()
        entry.state.update { it.copy(loading = true, failed = false) }
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
                        entry.edits.entries.removeAll { (id, item) -> (id in reportedIds) == (item != null) }
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
        val items = applyEdits(batch.items, entry.edits)
        publish(location, entry, entry.state.value.copy(items = items,
            total = if (finished) items.size else batch.total, loaded = items.size, loading = !finished,
        ), trackDepartures = finished)
    }

    private fun publish(location: AlbumLocation, entry: Entry, next: AlbumContentsState, trackDepartures: Boolean = true) {
        val previous = entry.state.value
        val ids = next.items.mapTo(mutableSetOf()) { it.mediaId }
        entry.state.value = next.copy(departedIds =
            (previous.departedIds + if (trackDepartures) previous.items.map { it.mediaId } else emptyList()) - ids,
        )
        if (entry.initialized) complete.update { it + (location to next.items) }
    }

    private fun applyEdits(items: List<MediaItem>, edits: Map<Long, MediaItem?>): List<MediaItem> =
        (items.filterNot { it.remoteFileId in edits } + edits.values.filterNotNull()).distinctBy { it.mediaId }
}

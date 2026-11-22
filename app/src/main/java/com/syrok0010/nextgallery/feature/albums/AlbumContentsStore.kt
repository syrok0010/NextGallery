package com.syrok0010.nextgallery.feature.albums

import androidx.annotation.MainThread
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

internal data class AlbumContentsState(
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val loaded: Int = 0,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val permissionRequired: Boolean = false,
    val pendingMetadata: Int = 0,
)

/** Lazily reads only locations that are opened or already cached by a mutation. */
@MainThread
internal class AlbumContentsStore(
    private val credentials: AccountCredentials,
    private val source: AlbumContentsSource,
    private val library: MediaLibraryIndex,
    private val permission: StateFlow<LocalMediaPermissionMode?>,
    private val scope: CoroutineScope,
) {
    private val entries = mutableMapOf<AlbumLocation, Entry>()
    private val complete = MutableStateFlow<Map<AlbumLocation, AlbumContentsState>>(emptyMap())
    val snapshots: StateFlow<Map<AlbumLocation, AlbumContentsState>> = complete.asStateFlow()

    private class Entry(initial: AlbumContentsState) {
        var membership = AlbumMembership()
        val state = MutableStateFlow(initial)
        var job: Job? = null
        var request = 0L
    }

    init {
        scope.launch {
            library.state.collect { known ->
                if (entries.values.none { it.membership.hasPendingMetadata }) return@collect
                val metadata = known.values.associateBy { it.remoteFileId }
                entries.forEach { (location, entry) ->
                    if (entry.membership.hasPendingMetadata) {
                        entry.membership = entry.membership.resolve(metadata)
                        publish(location, entry)
                    }
                }
            }
        }
        scope.launch {
            permission.collectLatest { access ->
                entries.forEach { (location, entry) ->
                    if (location is AlbumLocation.Folder && access != LocalMediaPermissionMode.Full) {
                        entry.job?.cancel()
                        entry.request++
                        entry.membership = AlbumMembership()
                        entry.state.value = AlbumContentsState(permissionRequired = true)
                        complete.value -= location
                    } else if (location is AlbumLocation.Folder && entry.job?.isActive != true && !entry.membership.initialized) {
                        load(location, entry, authoritative = false)
                    }
                }
            }
        }
    }

    private fun entry(location: AlbumLocation): Entry = entries.getOrPut(location) {
        val blocked = location is AlbumLocation.Folder && permission.value != LocalMediaPermissionMode.Full
        Entry(AlbumContentsState(loading = !blocked, permissionRequired = blocked))
    }

    fun observe(location: AlbumLocation): Flow<AlbumContentsState> = flow {
        val entry = entry(location)
        if ((!entry.membership.initialized || entry.membership.hasPendingMetadata) && entry.job?.isActive != true) {
            load(location, entry, authoritative = false)
        }
        emitAll(
            combine(entry.state, library.state, permission) { state, known, access ->
                if (location is AlbumLocation.Folder && access != LocalMediaPermissionMode.Full) {
                    AlbumContentsState(permissionRequired = true)
                } else {
                    state.copy(items = albumMediaProjection(state.items, known, access == LocalMediaPermissionMode.Full))
                }
            },
        )
    }

    fun refresh(location: AlbumLocation? = null) {
        entries.forEach { (key, entry) -> if (location == null || key == location) load(key, entry, authoritative = true) }
    }

    fun reconcile(location: AlbumLocation) {
        entries[location]?.let { load(location, it, authoritative = false) }
    }

    fun refreshFolders() {
        entries.forEach { (location, entry) -> if (location is AlbumLocation.Folder) load(location, entry, authoritative = true) }
    }

    fun added(location: AlbumLocation.Remote, ids: Set<Long>, supplied: List<MediaItem>): Boolean {
        val entry = entries[location] ?: return false
        val metadata = library.state.value.values.associateBy { it.remoteFileId }
        entry.membership = entry.membership.added(ids, supplied).resolve(metadata)
        publish(location, entry)
        return entry.membership.initialized && !entry.membership.hasPendingMetadata
    }

    private fun load(location: AlbumLocation, entry: Entry, authoritative: Boolean) {
        if (location is AlbumLocation.Folder && permission.value != LocalMediaPermissionMode.Full) return
        entry.job?.cancel()
        val request = ++entry.request
        val startedAtVersion = entry.membership.version
        val hadSnapshot = entry.membership.initialized || entry.membership.snapshot.items.isNotEmpty()
        entry.state.value = entry.state.value.copy(loading = true, failed = false, permissionRequired = false)
        entry.job = scope.launch {
            try {
                var latest = entry.membership.snapshot
                source.load(location).collect { batch ->
                    if (entry.request != request) return@collect
                    latest = batch
                    if (!hadSnapshot) {
                        entry.membership = entry.membership.applyRead(batch, startedAtVersion, authoritative, finished = false)
                        publish(location, entry)
                    }
                }
                if (entry.request == request) {
                    entry.membership = entry.membership.applyRead(latest, startedAtVersion, authoritative, finished = true)
                    entry.state.value = entry.state.value.copy(loading = false)
                    publish(location, entry)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
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
        )
        entry.state.value = state
        if (entry.membership.initialized) complete.value += location to state
    }

}

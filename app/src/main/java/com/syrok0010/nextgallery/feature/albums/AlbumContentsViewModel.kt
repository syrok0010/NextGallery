package com.syrok0010.nextgallery.feature.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.timeline.TimelineScreenState
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AlbumContentsState(
    val location: AlbumLocation? = null,
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val loaded: Int = 0,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val permissionRequired: Boolean = false,
)

internal class AlbumContentsViewModel(
    sessionStore: SessionStore,
    source: AlbumContentsSource,
    library: StateFlow<TimelineScreenState>,
    permission: StateFlow<LocalMediaPermissionMode?>,
) : ViewModel() {
    private val selection = MutableStateFlow<AlbumLocation?>(null)
    private val refreshes = MutableStateFlow(0)
    private val mutableState = MutableStateFlow(AlbumContentsState())
    private val knownItems = library
        .map { it.timeline.snapshot }
        .distinctUntilChanged()
        .map { it?.items.orEmpty().associateBy { media -> media.mediaId } }
    val state = combine(mutableState, knownItems, permission) { contents, libraryItems, access ->
        if (contents.location is AlbumLocation.Folder && access != LocalMediaPermissionMode.Full) {
            contents.copy(
                items = emptyList(),
                total = 0,
                loaded = 0,
                loading = false,
                permissionRequired = true,
            )
        } else {
            contents.copy(
                items = albumMediaProjection(
                    contents.items,
                    libraryItems,
                    access == LocalMediaPermissionMode.Full,
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AlbumContentsState())

    fun select(location: AlbumLocation?) {
        selection.value = location
    }

    fun refresh() {
        refreshes.update { it + 1 }
    }

    init {
        viewModelScope.launch {
            combine(
                sessionStore.session,
                selection,
                permission,
                refreshes,
            ) { session, location, mode, refresh ->
                LoadRequest(
                    session,
                    location,
                    location is AlbumLocation.Folder && mode != LocalMediaPermissionMode.Full,
                    refresh,
                )
            }.distinctUntilChanged().collectLatest { (session, location, needsPermission) ->
                mutableState.value = AlbumContentsState(location = location)
                if (session !is SessionUiState.SignedIn || location == null) return@collectLatest
                if (needsPermission) {
                    mutableState.value = AlbumContentsState(
                        location = location,
                        permissionRequired = true,
                    )
                    return@collectLatest
                }
                mutableState.update { it.copy(loading = true) }
                try {
                    source.load(location, session.credentials).collect { batch ->
                        mutableState.update {
                            it.copy(
                                items = batch.items,
                                total = batch.total,
                                loaded = batch.loaded,
                            )
                        }
                    }
                    mutableState.update { it.copy(loading = false) }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    _: Exception,
                ) {
                    mutableState.update { it.copy(loading = false, failed = true) }
                }
            }
        }
    }
}

private data class LoadRequest(
    val session: SessionUiState,
    val location: AlbumLocation?,
    val needsPermission: Boolean,
    val refresh: Int,
)

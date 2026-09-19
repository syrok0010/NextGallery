package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val items: List<AlbumSummary> = albumCatalog(remote.items, local.items)
}

@OptIn(FlowPreview::class)
internal class AlbumCatalogRepository(
    sessionStore: SessionStore,
    remoteSource: RemoteAlbumSource,
    localSource: LocalAlbumSource,
    private val permission: StateFlow<LocalMediaPermissionMode?>,
    scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(AlbumsUiState())
    val state = mutableState.asStateFlow()
    private val refreshes = MutableStateFlow(0)

    fun refresh() {
        refreshes.update { it + 1 }
    }

    init {
        scope.launch {
            sessionStore.session.collectLatest { session ->
                mutableState.value = AlbumsUiState(permission = permission.value)
                if (session !is SessionUiState.SignedIn) return@collectLatest
                coroutineScope {
                    launch {
                        refreshes.collectLatest {
                            mutableState.update {
                                it.copy(
                                    remote = it.remote.copy(
                                        loading = true,
                                        failed = false,
                                    ),
                                )
                            }
                            try {
                                val result = remoteSource.load(session.credentials)
                                mutableState.update {
                                    it.copy(
                                        remote = AlbumSourceState(
                                            result.albums,
                                            supported = result.supported,
                                        ),
                                    )
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                mutableState.update {
                                    it.copy(
                                        remote = it.remote.copy(
                                            loading = false,
                                            failed = true,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    launch {
                        permission.collectLatest { mode ->
                            mutableState.update {
                                it.copy(
                                    local = AlbumSourceState(),
                                    permission = mode,
                                )
                            }
                            if (mode != LocalMediaPermissionMode.Full) return@collectLatest
                            coroutineScope {
                                val localChanges = MutableStateFlow(0)
                                launch {
                                    localSource
                                        .changes()
                                        .debounce(450.milliseconds)
                                        .collect { localChanges.update { it + 1 } }
                                }
                                combine(
                                    refreshes,
                                    localChanges,
                                ) { refresh, change -> refresh to change }.collectLatest {
                                    mutableState.update {
                                        it.copy(
                                            local = it.local.copy(
                                                loading = true,
                                                failed = false,
                                            ),
                                        )
                                    }
                                    try {
                                        val albums = localSource.load()
                                        mutableState.update {
                                            it.copy(
                                                local = AlbumSourceState(
                                                    albums,
                                                ),
                                            )
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        mutableState.update {
                                            it.copy(
                                                local = it.local.copy(
                                                    loading = false,
                                                    failed = true,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

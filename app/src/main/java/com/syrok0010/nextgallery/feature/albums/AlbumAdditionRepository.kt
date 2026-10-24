package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.core.ui.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface AlbumTarget {
    val name: String

    data class Existing(val location: AlbumLocation.Remote, override val name: String) : AlbumTarget

    data class New(override val name: String) : AlbumTarget
}

/**
 * Server copies join the album directly; local-only objects are uploaded by the caller once the
 * album address is known and join the album after the upload.
 */
internal data class AlbumAdditionRequest(val fileIds: List<Long>, val localUploads: Int) {
    companion object {
        fun of(items: List<MediaItem>) =
            AlbumAdditionRequest(
                fileIds = items.mapNotNull { it.remoteFileId }.distinct(),
                localUploads = items.count { it.remoteFileId == null },
            )
    }
}

internal enum class AlbumAdditionFailure { AlbumExists, Network, NotWritable }

internal sealed interface AlbumAdditionState {
    data object Idle : AlbumAdditionState

    data class Running(val albumName: String, val count: Int) : AlbumAdditionState

    data class Done(val albumName: String, val result: AlbumAddResult, val localUploads: Int) :
        AlbumAdditionState

    data class Failed(val albumName: String, val reason: AlbumAdditionFailure) : AlbumAdditionState
}

/** Albums the current user may write without collaborator rules: their own Photos albums. */
internal data class AlbumChoices(val user: String?, val failed: Boolean = false)

internal fun writableAlbums(albums: List<AlbumSummary>, user: String): List<AlbumSummary> =
    albums.filter {
        val location = it.location as? AlbumLocation.Remote
        location != null && AlbumAddress.of(location)?.owner == user
    }

internal class AlbumAdditionRepository(
    private val sessions: SessionStore,
    private val writer: MemoriesAlbumWriter,
    private val catalog: AlbumCatalogRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<AlbumAdditionState>(AlbumAdditionState.Idle)
    val state = mutableState.asStateFlow()
    private val mutableChoices = MutableStateFlow(AlbumChoices(user = null))
    val choices = mutableChoices.asStateFlow()
    private var userJob: Job? = null
    private var job: Job? = null

    init {
        scope.launch {
            var owner: AccountCredentials? = null
            sessions.session.collect { session ->
                val current = (session as? SessionUiState.SignedIn)?.credentials
                // The first emission only records the owner; results never cross accounts.
                if (current == null || (owner != null && current != owner)) {
                    userJob?.cancel()
                    job?.cancel()
                    mutableChoices.value = AlbumChoices(user = null)
                    mutableState.value = AlbumAdditionState.Idle
                }
                owner = current
            }
        }
    }

    /** Resolves the DAV user id once per session; login names may differ from it. */
    fun prepare() {
        if (mutableChoices.value.user != null || userJob?.isActive == true) return
        val credentials = credentials() ?: return
        mutableChoices.value = AlbumChoices(user = null)
        userJob = scope.launch {
            mutableChoices.value = try {
                AlbumChoices(writer.currentUser(credentials))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AlbumChoices(user = null, failed = true)
            }
        }
    }

    /**
     * Returns false when the request is not accepted: another addition is running or the account
     * is not ready. The caller keeps the selection in that case.
     *
     * [onAlbumReady] runs on [scope] once the album exists, before server copies are added, so
     * uploads never race album creation. It is not called when the album cannot be written.
     */
    fun add(
        target: AlbumTarget,
        request: AlbumAdditionRequest,
        onAlbumReady: (AlbumAddress) -> Unit = {},
    ): Boolean {
        if (job?.isActive == true) return false
        val credentials = credentials() ?: return false
        val user = mutableChoices.value.user ?: return false
        mutableState.value = AlbumAdditionState.Running(target.name, request.fileIds.size)
        job = scope.launch {
            mutableState.value = try {
                val location = when (target) {
                    is AlbumTarget.Existing -> target.location
                    is AlbumTarget.New -> writer.create(credentials, user, target.name.trim())
                }
                val address = AlbumAddress.of(location)
                if (address == null || address.owner != user) {
                    AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.NotWritable)
                } else {
                    onAlbumReady(address)
                    AlbumAdditionState.Done(
                        target.name,
                        writer.add(credentials, user, address, request.fileIds),
                        request.localUploads,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AlbumAlreadyExistsException) {
                AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.AlbumExists)
            } catch (_: Exception) {
                AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.Network)
            }
            catalog.refresh()
        }
        return true
    }

    /** Called once the UI has shown a terminal result. */
    fun consume() {
        val current = mutableState.value
        if (current is AlbumAdditionState.Done || current is AlbumAdditionState.Failed) {
            mutableState.value = AlbumAdditionState.Idle
        }
    }

    private fun credentials(): AccountCredentials? =
        (sessions.session.value as? SessionUiState.SignedIn)?.credentials
}

/** Message parts joined by the snackbar; zero counters stay silent. */
internal fun albumAdditionMessage(state: AlbumAdditionState): List<UiText>? =
    when (state) {
        AlbumAdditionState.Idle -> null

        is AlbumAdditionState.Running -> listOf(uiText(R.string.album_add_running, state.albumName))

        is AlbumAdditionState.Done -> buildList {
            val total = state.result.added + state.result.alreadyPresent + state.result.failed
            if (total > 0 || state.localUploads == 0) {
                add(uiText(R.string.album_add_done, state.albumName, state.result.added))
            }
            if (state.result.alreadyPresent > 0) {
                add(uiText(R.string.album_add_done_present, state.result.alreadyPresent))
            }
            if (state.result.failed > 0) {
                add(
                    uiText(R.string.album_add_done_failed, state.result.failed),
                )
            }
            if (state.localUploads > 0) {
                add(
                    if (total > 0) {
                        uiText(R.string.album_add_done_uploading, state.localUploads)
                    } else {
                        uiText(
                            R.string.album_add_uploading_only,
                            state.albumName,
                            state.localUploads,
                        )
                    },
                )
            }
        }

        is AlbumAdditionState.Failed -> listOf(
            uiText(
                when (state.reason) {
                    AlbumAdditionFailure.AlbumExists -> R.string.album_add_failed_exists
                    AlbumAdditionFailure.Network -> R.string.album_add_failed_network
                    AlbumAdditionFailure.NotWritable -> R.string.album_add_failed_not_writable
                },
                state.albumName,
            ),
        )
    }

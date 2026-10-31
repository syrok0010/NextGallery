package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.core.ui.pluralText
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.upload.UploadQueue
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
 * Server copies join the album directly; objects without one ([uploads]) are uploaded once the
 * album exists and join it after the upload.
 */
internal data class AlbumAdditionRequest(
    val fileIds: List<Long>,
    val uploads: List<MediaId> = emptyList(),
) {
    companion object {
        fun of(items: List<MediaItem>) =
            AlbumAdditionRequest(
                fileIds = items.mapNotNull { it.remoteFileId }.distinct(),
                uploads = items.filterNot { it.hasRemoteCopy }.map { it.mediaId },
            )
    }
}

internal enum class AlbumAdditionFailure { AlbumExists, Network, NotWritable }

internal sealed interface AlbumAdditionState {
    data object Idle : AlbumAdditionState

    /** [fileIds] are the server copies being copied into the album. */
    data class Running(val albumName: String, val fileIds: Set<Long>) : AlbumAdditionState

    data class Done(val albumName: String, val result: AlbumAddResult, val localUploads: Int) :
        AlbumAdditionState

    data class Failed(val albumName: String, val reason: AlbumAdditionFailure) : AlbumAdditionState
}

/** Albums the current user may write without collaborator rules: their own Photos albums. */
internal fun writableAlbums(albums: List<AlbumSummary>, user: String): List<AlbumSummary> =
    albums.filter {
        val location = it.location as? AlbumLocation.Remote
        location != null && AlbumAddress.of(location)?.owner == user
    }

/**
 * Runs album additions in the app scope, so one outlives the sheet and the screen that started it,
 * and keeps its state for the result message.
 */
internal class AlbumAdditions(
    private val sessions: SessionStore,
    private val writer: MemoriesAlbumWriter,
    private val catalog: AlbumCatalogRepository,
    private val uploads: UploadQueue,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<AlbumAdditionState>(AlbumAdditionState.Idle)
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch {
            var owner: AccountCredentials? = null
            sessions.session.collect { session ->
                val current = (session as? SessionUiState.SignedIn)?.credentials
                // The first emission only records the owner; results never cross accounts.
                if (current == null || (owner != null && current != owner)) {
                    job?.cancel()
                    mutableState.value = AlbumAdditionState.Idle
                }
                owner = current
            }
        }
    }

    /**
     * Returns false when the request is not accepted: another addition is running or nobody is
     * signed in. The caller keeps the selection in that case.
     *
     * Local-only objects are queued for upload once the album exists, so they never race album
     * creation; they are not uploaded when the album cannot be written.
     */
    fun add(target: AlbumTarget, request: AlbumAdditionRequest): Boolean {
        if (job?.isActive == true) return false
        val credentials = credentials() ?: return false
        mutableState.value = AlbumAdditionState.Running(target.name, request.fileIds.toSet())
        job = scope.launch {
            mutableState.value = try {
                val location = when (target) {
                    is AlbumTarget.Existing -> target.location
                    is AlbumTarget.New -> writer.create(credentials, target.name.trim())
                }
                val address = AlbumAddress.of(location)
                if (address == null || address.owner != credentials.userId) {
                    AlbumAdditionState.Failed(target.name, AlbumAdditionFailure.NotWritable)
                } else {
                    val queued = uploads.submit(
                        request.uploads,
                        listOf(AlbumUploadFollowUp(writer, catalog, address)),
                    )
                    AlbumAdditionState.Done(
                        target.name,
                        writer.add(credentials, address, request.fileIds),
                        queued,
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

/** Message parts of the status line; zero counters stay silent. */
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
                    pluralText(
                        R.plurals.album_add_done_failed,
                        state.result.failed,
                        state.result.failed,
                    ),
                )
            }
            if (state.localUploads > 0) {
                add(
                    if (total > 0) {
                        pluralText(
                            R.plurals.album_add_done_uploading,
                            state.localUploads,
                            state.localUploads,
                        )
                    } else {
                        pluralText(
                            R.plurals.album_add_uploading_only,
                            state.localUploads,
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

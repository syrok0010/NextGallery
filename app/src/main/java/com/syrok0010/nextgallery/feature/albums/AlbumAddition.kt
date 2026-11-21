package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import com.syrok0010.nextgallery.core.operation.OperationKind
import com.syrok0010.nextgallery.core.operation.OperationMediaActivity
import com.syrok0010.nextgallery.core.operation.OperationMediaReference
import com.syrok0010.nextgallery.core.operation.OperationStatusSnapshot
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.core.ui.pluralText
import com.syrok0010.nextgallery.core.ui.uiText

internal sealed interface AlbumTarget {
    val name: String

    data class Existing(val location: AlbumLocation.Remote, override val name: String) : AlbumTarget

    data class New(override val name: String) : AlbumTarget
}

/**
 * Server copies join the album directly; local-only [uploads] are uploaded once the album exists
 * and join it after the upload.
 */
internal data class AlbumAdditionRequest(
    val fileIds: List<Long>,
    val uploads: List<MediaId> = emptyList(),
    val items: List<MediaItem> = emptyList(),
) {
    companion object {
        fun of(items: List<MediaItem>) =
            AlbumAdditionRequest(
                fileIds = items.mapNotNull { it.remoteFileId }.distinct(),
                uploads = items.filterNot { it.hasRemoteCopy }.map { it.mediaId },
                items = items,
            )
    }
}

internal enum class AlbumAdditionFailure { AlbumExists, Network, NotWritable, QueueStorage }

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

internal fun albumOperationSnapshots(state: AlbumAdditionState): List<OperationStatusSnapshot> {
    val lines = albumAdditionMessage(state) ?: return emptyList()
    val running = state as? AlbumAdditionState.Running
    val failed = state is AlbumAdditionState.Failed ||
        (state as? AlbumAdditionState.Done)?.result?.failed?.let { it > 0 } == true
    return listOf(
        OperationStatusSnapshot(
            kind = OperationKind.AddToAlbum,
            title = lines.first(),
            details = lines.drop(1),
            running = running != null,
            failed = failed,
            media = running?.fileIds.orEmpty().map {
                OperationMediaActivity(OperationMediaReference.RemoteFileId(it))
            },
        ),
    )
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
                    AlbumAdditionFailure.QueueStorage -> R.string.uploads_enqueue_rejected
                },
                state.albumName,
            ),
        )
    }

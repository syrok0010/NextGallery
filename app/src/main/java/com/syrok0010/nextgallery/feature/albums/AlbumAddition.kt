package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasLocalCopy
import com.syrok0010.nextgallery.core.operation.OperationKind
import com.syrok0010.nextgallery.core.operation.OperationMediaActivity
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
 * Copies of the selection fixed when the album picker opens. Physical server ids stay inside the
 * albums module; callers only read counts and pass the preparation back to [Albums.add].
 */
internal class AlbumAdditionPreparation private constructor(
    internal val cloud: List<CloudMember>,
    internal val uploads: List<MediaId>,
) {
    val cloudCount: Int get() = cloud.size
    val uploadCount: Int get() = uploads.size
    val hasUploads: Boolean get() = uploads.isNotEmpty()
    internal val cloudMediaIds: Set<MediaId> get() = cloud.mapTo(LinkedHashSet()) { it.mediaId }
    internal val fileIds: List<Long> get() = cloud.map { it.fileId }.distinct()

    internal data class CloudMember(val mediaId: MediaId, val fileId: Long)

    internal companion object {
        /** Objects without any available copy are skipped; null when nothing remains to add. */
        fun of(items: Collection<MediaItem>): AlbumAdditionPreparation? {
            val unique = items.distinctBy { it.mediaId }
            val cloud = unique.mapNotNull { item ->
                item.remoteFileId?.let {
                    CloudMember(
                        item.mediaId,
                        it,
                    )
                }
            }
            val uploads = unique
                .filter {
                    it.remoteFileId == null && it.hasLocalCopy
                }.map { it.mediaId }
            return AlbumAdditionPreparation(cloud, uploads)
                .takeIf { it.cloudCount > 0 || it.uploadCount > 0 }
        }
    }
}

internal enum class AlbumAdditionFailure { AlbumExists, Network, NotWritable, QueueStorage }

internal sealed interface AlbumAdditionState {
    data object Idle : AlbumAdditionState

    /** [mediaIds] are the objects whose cloud copies are being copied into the album. */
    data class Running(val albumName: String, val mediaIds: Set<MediaId>) : AlbumAdditionState

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
            media = running?.mediaIds.orEmpty().map { OperationMediaActivity(it) },
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

internal sealed interface AlbumRemovalState {
    data object Idle : AlbumRemovalState
    data class Running(val name: String, val mediaIds: Set<MediaId>) : AlbumRemovalState
    data class Done(val name: String, val result: AlbumRemovalResult) : AlbumRemovalState
}

internal fun removalOperationSnapshots(state: AlbumRemovalState): List<OperationStatusSnapshot> =
    when (state) {
        AlbumRemovalState.Idle -> emptyList()

        is AlbumRemovalState.Running -> listOf(
            OperationStatusSnapshot(
                kind = OperationKind.RemoveFromAlbum,
                title = uiText(R.string.album_remove_running, state.name),
                running = true,
                media = state.mediaIds.map { OperationMediaActivity(it) },
            ),
        )

        is AlbumRemovalState.Done -> listOf(
            OperationStatusSnapshot(
                kind = OperationKind.RemoveFromAlbum,
                title = uiText(R.string.album_remove_done, state.result.removed),
                details = if (state.result.failed == 0) {
                    emptyList()
                } else {
                    listOf(uiText(R.string.album_remove_failed, state.result.failed))
                },
                failed = state.result.failed > 0,
            ),
        )
    }

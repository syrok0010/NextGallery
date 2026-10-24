package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.upload.UploadFollowUp
import com.syrok0010.nextgallery.feature.upload.UploadSelection
import com.syrok0010.nextgallery.feature.upload.UploadedFile
import kotlinx.coroutines.CancellationException

/** Adds local-only objects to [album] once their upload reaches the server. */
internal class AlbumUploadFollowUp(
    private val writer: MemoriesAlbumWriter,
    private val catalog: AlbumCatalogRepository,
    private val album: AlbumAddress,
) : UploadFollowUp {
    private val outcomes = mutableListOf<AlbumCopyOutcome>()

    override suspend fun uploaded(credentials: AccountCredentials, file: UploadedFile) {
        outcomes += try {
            writer.addFile(credentials, file.url, album)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AlbumCopyOutcome.Failed
        }
    }

    override suspend fun finished(missed: List<UploadSelection>): List<UiText> {
        // The album sheet refreshed the catalog before these files reached the album.
        if (outcomes.isNotEmpty()) catalog.refresh()
        return listOfNotNull(albumUploadSummary(album.name, outcomes, missed.size))
    }
}

/**
 * Notification line for one album; files already in the album count as added, files that never
 * reached the server count as not added.
 */
internal fun albumUploadSummary(
    album: String,
    outcomes: List<AlbumCopyOutcome>,
    missed: Int,
): UiText? {
    if (outcomes.isEmpty() && missed == 0) return null
    val notAdded = outcomes.count { it == AlbumCopyOutcome.Failed } + missed
    val added = outcomes.size + missed - notAdded
    return if (notAdded > 0) {
        uiText(R.string.album_upload_summary_failed, album, added, notAdded)
    } else {
        uiText(R.string.album_upload_summary, album, added)
    }
}

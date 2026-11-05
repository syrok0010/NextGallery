package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.upload.AlbumStepMode
import com.syrok0010.nextgallery.feature.upload.UploadIntent
import com.syrok0010.nextgallery.feature.upload.UploadStepResult
import com.syrok0010.nextgallery.feature.upload.UploadedFile
import kotlinx.coroutines.CancellationException

/**
 * Durable album follow-up boundary. The first accepted receipt may perform the explicit COPY;
 * recovery only reuses the server-side writer's idempotent membership check.
 */
internal class DurableAlbumStep(private val writer: MemoriesAlbumWriter) {
    suspend fun snapshot(account: AccountCredentials, intent: UploadIntent): UploadIntent {
        if (intent.kind != "album" || intent.version != 1 || intent.owner != account.userId) return intent
        return intent.copy(version = 2, albumId = intent.albumId ?: 1L, albumHref = "${intent.owner}/${intent.target}")
    }

    suspend fun run(
        account: AccountCredentials,
        intent: UploadIntent,
        file: UploadedFile,
        mode: AlbumStepMode,
        current: suspend () -> Boolean,
    ): UploadStepResult {
        try {
            if (!current()) return UploadStepResult.attention("Stopped")
            if (intent.version != 2 || intent.owner != account.userId) {
                return UploadStepResult.attention("LegacyTarget")
            }
            if (mode != AlbumStepMode.Initial) return UploadStepResult.attention("ReplayUnsupported")
            val path = file.url.pathSegments
            val filesIndex = path.indexOf("files")
            if (filesIndex < 0 || filesIndex + 2 >= path.size) {
                return UploadStepResult.attention("SourceChanged")
            }
            val source = DavFilePath(account.userId, path.drop(filesIndex + 2))
            val outcome = writer.addFile(source, AlbumAddress(intent.owner, intent.target))
            return when (outcome) {
                AlbumCopyOutcome.Added, AlbumCopyOutcome.AlreadyPresent ->
                    UploadStepResult("Done", fileId = file.fileId, targetId = intent.albumId, href = file.url.toString())
                AlbumCopyOutcome.Failed -> UploadStepResult.uncertain()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return UploadStepResult.uncertain()
        }
    }
}

package com.syrok0010.nextgallery.feature.upload

import android.content.ContentResolver
import android.net.Uri
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.capabilities.NextcloudCapabilitiesClient
import com.syrok0010.nextgallery.core.network.dav.CreateFileResult
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.DavHttpException
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.network.dav.UploadsDavClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.FileNotFoundException
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import retrofit2.HttpException

/**
 * [selection] is on the server at [path]: uploaded now ([created]) or already there with the same
 * bytes. [path] may be a neighbour name when the original one holds a different file. [fileId] is
 * the server file id when DAV returned it.
 */
internal data class UploadedFile(
    val selection: UploadSelection,
    val path: DavFilePath,
    val created: Boolean,
    val fileId: Long? = null,
)

/**
 * Puts the bytes of one job into the timeline folder (ADR 0014). One instance serves one pass, so
 * the folder is read from Memories once per pass.
 */
internal class UploadTransfer(
    private val resolver: ContentResolver,
    private val transport: NextcloudTransport,
    private val memories: MemoriesApiClient,
    private val queue: UploadQueue,
) {
    private var directory: String? = null
    private var partSize: Long? = null

    /**
     * The original name in the timeline folder; after a restart its neighbours are checked again.
     * Returns null when no free name is left for a file that differs from the taken ones.
     */
    suspend fun upload(account: AccountCredentials, job: UploadJob, onProgress: (Int?) -> Unit): UploadedFile? {
        val pinned = job.destination?.let { DavFilePath(account.userId, it) }
            ?: queue.pin(job, DavFilePath.of(account.userId, directory(), job.selection.displayName))
        return try {
            put(account, job.id, pinned, job.selection, onProgress)
        } catch (_: SourceUnavailable) {
            throw UploadException(UploadProblem.Source)
        }
    }

    private suspend fun directory(): String = directory ?: timelineDirectory().also { directory = it }

    /** Part size the server admin set for chunked uploads; 0 or less turns them off. */
    private suspend fun partSize(account: AccountCredentials): Long =
        partSize ?: try {
            NextcloudCapabilitiesClient(transport) { account }.capabilities().files?.chunkedUpload?.maxSize
                // The server default, for one that does not publish it.
                ?: DEFAULT_PART_SIZE
        } catch (error: HttpException) {
            throw UploadException(UploadProblem.Http, error.code())
        }.also { partSize = it }

    private suspend fun timelineDirectory(): String {
        val config = try {
            memories.config()
        } catch (error: HttpException) {
            throw UploadException(UploadProblem.Memories, error.code())
        }
        return timelinePaths(config.timelinePath).firstOrNull() ?: throw UploadException(UploadProblem.Timeline)
    }

    private suspend fun put(
        account: AccountCredentials,
        id: String,
        path: DavFilePath,
        selection: UploadSelection,
        onProgress: (Int?) -> Unit,
    ): UploadedFile? {
        val uri = Uri.parse(selection.contentUri)
        val length = readSource {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        } ?: -1L
        onProgress(sentPercent(0L, length))
        val openLocal = {
            val stream = readSource { resolver.openInputStream(uri) }
            stream ?: throw SourceUnavailable()
        }
        // Parts already sent count as sent.
        fun body(offset: Long, size: Long) =
            UploadRequestBody(size, selection.mimeType?.toMediaTypeOrNull(), openLocal, offset) { sent ->
                onProgress(sentPercent(offset + sent, length))
            }
        // Signed by the job's account, not whichever session is current by now.
        val dav = FilesDavClient(transport) { account }
        val uploads = UploadsDavClient(transport) { account }
        val partSize = if (length > 0L) partSize(account) else 0L
        val create: suspend (DavFilePath) -> CreateFileResult = { target ->
            // Like the Nextcloud web client: only a file larger than one part is chunked.
            if (partSize in 1 until length) {
                // Named by the job, so a later attempt finds the parts this one stored.
                createInParts(uploads, id, target, length, partSize, ::body)
            } else {
                dav.createFile(target, body(0L, length))
            }
        }
        return try {
            placeUpload(dav, path, selection, length, create, openLocal)
        } catch (error: DavHttpException) {
            val problem = if (error.code == 404 || error.code == 409) UploadProblem.Directory else UploadProblem.Http
            throw UploadException(problem, error.code)
        }
    }

    /**
     * The local file can no longer be read. An [IOException], so OkHttp passes it out of the
     * request body unchanged instead of rethrowing it on its dispatcher thread.
     */
    private class SourceUnavailable(cause: Throwable? = null) : IOException(cause)

    /** Turns a file that vanished or lost its permission into [SourceUnavailable]. */
    private inline fun <T> readSource(open: () -> T): T =
        try {
            open()
        } catch (error: FileNotFoundException) {
            throw SourceUnavailable(error)
        } catch (error: SecurityException) {
            throw SourceUnavailable(error)
        }
}

private const val DEFAULT_PART_SIZE = 100L * 1024 * 1024

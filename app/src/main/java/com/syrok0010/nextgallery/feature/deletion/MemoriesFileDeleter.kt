package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.await
import com.syrok0010.nextgallery.core.network.fileUrl
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class CloudDeleteResult(val deleted: Int, val failed: Int)

/** A file that is already gone (404) is what the user asked for, so it counts as deleted. */
internal fun cloudDeleteSucceeded(code: Int): Boolean = code in 200..299 || code == 404

/**
 * Deletes server copies through DAV, as Memories Web does. With the Nextcloud trash bin app
 * enabled, a DAV DELETE moves the file to the user's trash instead of erasing it.
 */
internal class MemoriesFileDeleter(
    private val transport: NextcloudTransport,
    private val files: MemoriesFiles,
) {
    suspend fun delete(credentials: AccountCredentials, fileIds: List<Long>): CloudDeleteResult {
        val permits = Semaphore(PARALLEL_DELETES)
        val outcomes = coroutineScope {
            fileIds
                .distinct()
                .map { fileId ->
                    async {
                        permits.withPermit {
                            try {
                                val file = files.file(credentials, fileId)
                                delete(
                                    credentials,
                                    fileUrl(credentials.serverUrl, credentials.userId, file.path),
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                        }
                    }
                }.awaitAll()
        }
        return CloudDeleteResult(deleted = outcomes.count { it }, failed = outcomes.count { !it })
    }

    private suspend fun delete(credentials: AccountCredentials, url: okhttp3.HttpUrl): Boolean {
        val request = transport
            .authenticatedRequestBuilder(credentials, url.toString(), accept = "*/*")
            .delete()
            .build()
        return transport.baseClient.newCall(request).await().use { cloudDeleteSucceeded(it.code) }
    }

    private companion object {
        const val PARALLEL_DELETES = 4
    }
}

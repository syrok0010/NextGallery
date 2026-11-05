package com.syrok0010.nextgallery.feature.timeline.remote

import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient

/**
 * Location of a Memories file in the user's DAV files: [path] from the files root, [name].
 * [etag] matches DAV `getetag` without quotes and pins writes to this exact file.
 */
internal data class RemoteFile(val path: String, val name: String, val etag: String? = null)

/**
 * Bridges Memories file ids to Nextcloud DAV. Memories has no write API, so writes go through
 * DAV paths of the account's uid.
 */
internal class MemoriesFiles(private val memories: MemoriesApiClient) {
    suspend fun file(fileId: Long): RemoteFile {
        val info = memories.imageInfo(fileId)
        return RemoteFile(
            path = info.filename,
            name = info.basename,
            etag = info.etag?.takeIf(String::isNotBlank),
        )
    }
}

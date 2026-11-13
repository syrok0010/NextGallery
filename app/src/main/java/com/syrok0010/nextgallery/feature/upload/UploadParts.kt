package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.network.dav.CreateFileResult
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.UploadsDavClient
import java.io.IOException
import okhttp3.RequestBody

/**
 * Sends a file of [length] bytes to [target] in parts of [partSize] under upload [id], keeping the
 * parts an interrupted attempt already stored. [part] streams one range of the file; [onSent] is
 * called once every part is on the server, before it joins them. Stored parts
 * of another size belong to another layout, so they are dropped and every part is sent again.
 *
 * An upload the server lost meanwhile is dropped and fails as temporary, so the next attempt
 * starts a new one. Object storage keeps the state of an upload apart from its folder: when it is
 * lost, the folder stays and only deleting it lets the upload start over.
 */
internal suspend fun createInParts(
    uploads: UploadsDavClient,
    id: String,
    target: DavFilePath,
    length: Long,
    partSize: Long,
    part: (offset: Long, size: Long) -> RequestBody,
    onSent: () -> Unit = {},
): CreateFileResult {
    require(length > 0L && partSize > 0L)
    val count = ((length + partSize - 1) / partSize).toInt()
    fun size(number: Int) = minOf(partSize, length - (number - 1) * partSize)
    val listed = uploads.parts(id)
    fun sameLayout(parts: Map<Int, Long>) =
        parts.all { (number, bytes) -> number in 1..count && bytes == size(number) }
    val stored = if (listed != null && sameLayout(listed)) {
        listed
    } else {
        if (listed != null) uploads.cancel(id)
        uploads.start(id, target)
        emptyMap()
    }
    val sent = (1..count).all { number ->
        stored[number] == size(number) ||
            uploads.putPart(id, number, target, length, part((number - 1) * partSize, size(number)))
    }
    if (sent) onSent()
    val result = if (sent) uploads.finish(id, target, length) else null
    // Lost, or a taken name: the parts were sent for it, and another name starts its own upload.
    if (result !is CreateFileResult.Created) uploads.cancel(id)
    return result ?: throw IOException("The server lost the upload")
}

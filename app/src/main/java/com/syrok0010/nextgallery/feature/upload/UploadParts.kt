package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.network.dav.CreateFileResult
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.UploadsDavClient
import java.io.IOException
import okhttp3.RequestBody

/**
 * Sends a file of [length] bytes to [target] in parts of [partSize] under upload [id], keeping the
 * parts an interrupted attempt already stored. [part] streams one range of the file. Stored parts
 * of another size belong to another layout, so they are dropped and every part is sent again.
 */
internal suspend fun createInParts(
    uploads: UploadsDavClient,
    id: String,
    target: DavFilePath,
    length: Long,
    partSize: Long,
    part: (offset: Long, size: Long) -> RequestBody,
): CreateFileResult {
    require(length > 0L && partSize > 0L)
    val count = ((length + partSize - 1) / partSize).toInt()
    fun size(number: Int) = minOf(partSize, length - (number - 1) * partSize)
    // The server may drop the upload between our calls; then it starts over once.
    repeat(2) {
        val listed = uploads.parts(id)
        val stored = if (listed != null &&
            listed.all { (number, bytes) ->
                number in 1..count &&
                    bytes == size(
                        number,
                    )
            }
        ) {
            listed
        } else {
            if (listed != null) uploads.cancel(id)
            uploads.start(id, target)
            emptyMap()
        }
        val sent = (1..count).all { number ->
            stored[number] == size(number) ||
                uploads.putPart(
                    id,
                    number,
                    target,
                    length,
                    part((number - 1) * partSize, size(number)),
                )
        }
        if (!sent) return@repeat
        when (val result = uploads.finish(id, target, length)) {
            null -> return@repeat

            // The parts were sent for this name; another name starts its own upload.
            CreateFileResult.AlreadyExists -> {
                uploads.cancel(id)
                return result
            }

            is CreateFileResult.Created -> return result
        }
    }
    throw IOException("The server dropped the upload")
}

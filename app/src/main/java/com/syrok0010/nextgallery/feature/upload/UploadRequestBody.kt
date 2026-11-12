package com.syrok0010.nextgallery.feature.upload

import java.io.EOFException
import java.io.InputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * Streams [length] bytes of a file from [offset]: the whole file or one part of it; a negative
 * [length] streams to the end. [onSent] gets the bytes of this body written so far, from zero on
 * every write.
 */
internal class UploadRequestBody(
    private val length: Long,
    private val mediaType: MediaType?,
    private val openStream: () -> InputStream,
    private val offset: Long = 0L,
    private val onSent: (Long) -> Unit,
) : RequestBody() {
    override fun contentType() = mediaType

    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        // OkHttp writes the body again after authentication/retry.
        var written = 0L
        onSent(written)
        openStream().use { stream ->
            // A file stream seeks; Okio's skip would read every skipped byte.
            if (stream.skip(offset) < offset) throw EOFException("The file is shorter than before")
            val input = stream.source()
            val end = if (length < 0L) Long.MAX_VALUE else length
            while (written < end) {
                val count = input.read(sink.buffer, minOf(SEGMENT, end - written))
                if (count < 0L) break
                sink.emitCompleteSegments()
                written += count
                onSent(written)
            }
        }
    }
}

/** Percent of [total] bytes [sent]; null while the size is unknown. 100 is left for the response. */
internal fun sentPercent(sent: Long, total: Long): Int? =
    when {
        total < 0L -> null
        total == 0L -> 0
        else -> (sent.toDouble() / total * 100).toInt().coerceIn(0, 99)
    }

private const val SEGMENT = 64L * 1024

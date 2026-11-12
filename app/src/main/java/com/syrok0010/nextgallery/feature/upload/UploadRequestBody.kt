package com.syrok0010.nextgallery.feature.upload

import java.io.InputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Streams [length] bytes of a file from [offset]: the whole file or one part of it. Progress is
 * the percentage of [total] bytes sent, counting the [offset] bytes before this body as sent. Null
 * progress means unknown length; 100 is reserved for the HTTP response.
 */
internal class UploadRequestBody(
    private val length: Long,
    private val mediaType: MediaType?,
    private val openStream: () -> InputStream,
    private val onProgress: (Int?) -> Unit,
    private val offset: Long = 0L,
    private val total: Long = length,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : RequestBody() {
    override fun contentType() = mediaType

    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        // Local state also resets when OkHttp writes this body again after authentication/retry.
        var written = 0L
        var lastProgress = percent(0L)
        var lastUpdate = monotonicMillis()
        onProgress(lastProgress)
        openStream().use { input ->
            input.skipFully(offset)
            val buffer = ByteArray(64 * 1024)
            while (length < 0L || written < length) {
                val wanted = if (length < 0L) {
                    buffer.size
                } else {
                    minOf(
                        buffer.size.toLong(),
                        length - written,
                    ).toInt()
                }
                val count = input.read(buffer, 0, wanted)
                if (count < 0) break
                sink.write(buffer, 0, count)
                written += count
                val progress = percent(written)
                val now = monotonicMillis()
                if (progress != lastProgress && now - lastUpdate >= 250L) {
                    onProgress(progress)
                    lastProgress = progress
                    lastUpdate = now
                }
            }
        }
    }

    private fun percent(written: Long): Int? =
        when {
            total < 0L -> null
            total == 0L -> 0
            else -> ((offset + written).toDouble() / total * 100).toInt().coerceIn(0, 99)
        }
}

/** Stops early at the end of the stream; the short body then fails the request. */
private fun InputStream.skipFully(count: Long) {
    var left = count
    while (left > 0L) {
        val skipped = skip(left)
        if (skipped > 0L) {
            left -= skipped
        } else if (read() < 0) {
            return
        } else {
            left--
        }
    }
}

package com.syrok0010.nextgallery.feature.upload

import java.io.EOFException
import java.io.InputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

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
        openStream().use { stream ->
            // A file stream seeks; Okio's skip would read every skipped byte.
            if (stream.skip(offset) < offset) throw EOFException("The file is shorter than before")
            val input = stream.source()
            // Unknown length: to the end of the file.
            val end = if (length < 0L) Long.MAX_VALUE else length
            while (written < end) {
                val count = input.read(sink.buffer, minOf(SEGMENT, end - written))
                if (count < 0L) break
                sink.emitCompleteSegments()
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

private const val SEGMENT = 64L * 1024

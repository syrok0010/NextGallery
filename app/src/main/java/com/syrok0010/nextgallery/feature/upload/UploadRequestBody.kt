package com.syrok0010.nextgallery.feature.upload

import java.io.InputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** Streams one file. Null progress means unknown length; 100 is reserved for the HTTP response. */
internal class UploadRequestBody(
    private val length: Long,
    private val mediaType: MediaType?,
    private val openStream: () -> InputStream,
    private val onProgress: (Int?) -> Unit,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : RequestBody() {
    override fun contentType() = mediaType

    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        // Local state also resets when OkHttp writes this body again after authentication/retry.
        var written = 0L
        var lastProgress = if (length >= 0L) 0 else null
        var lastUpdate = monotonicMillis()
        onProgress(lastProgress)
        openStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sink.write(buffer, 0, count)
                written += count
                if (length > 0L) {
                    val progress = (written.toDouble() / length * 100).toInt().coerceIn(0, 99)
                    val now = monotonicMillis()
                    if (progress != lastProgress && now - lastUpdate >= 250L) {
                        onProgress(progress)
                        lastProgress = progress
                        lastUpdate = now
                    }
                }
            }
        }
    }
}

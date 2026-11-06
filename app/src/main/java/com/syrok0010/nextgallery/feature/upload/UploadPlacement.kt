package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.network.dav.CreateFileResult
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.DavFileProperties
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.network.dav.ReadFileResult
import com.syrok0010.nextgallery.core.network.dav.url
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.RequestBody
import okio.buffer
import okio.source

/**
 * Puts [selection] at [path] on [serverUrl] or, while that name holds a different file, at the first free
 * neighbour `name (2).ext`, the way the Nextcloud server names copies. A file already at a name
 * counts as this upload only when its bytes equal the phone file: then its id links both copies
 * (ADR 0017). Returns null when every name up to [MAX_NAMES] holds a different file.
 *
 * Each name is checked before `PUT`: a web server takes the whole body before Nextcloud answers
 * `412`, so probing with `PUT` would send the file once per taken name.
 */
internal suspend fun placeUpload(
    dav: FilesDavClient,
    serverUrl: String,
    path: DavFilePath,
    selection: UploadSelection,
    body: RequestBody,
    openLocal: () -> InputStream,
): UploadedFile? {
    var index = 1
    var changes = 0
    while (index <= MAX_NAMES) {
        val candidate = if (index == 1) {
            path
        } else {
            path.copy(
                segments = path.segments.dropLast(1) + neighbourName(path.name, index),
            )
        }
        val existing = dav.properties(candidate)
        val outcome = if (existing == null) {
            when (val created = dav.createFile(candidate, body)) {
                is CreateFileResult.Created ->
                    return UploadedFile(
                        selection,
                        candidate.url(serverUrl),
                        created = true,
                        created.fileId,
                    )

                // Taken between the check and PUT.
                CreateFileResult.AlreadyExists -> Existing.Changed
            }
        } else {
            compare(dav, candidate, existing, body.contentLength(), openLocal)
        }
        when (outcome) {
            is Existing.Same ->
                return UploadedFile(
                    selection,
                    candidate.url(serverUrl),
                    created = false,
                    outcome.fileId,
                )

            Existing.Different -> index++

            // The name keeps changing under us: give it up rather than loop.
            Existing.Changed -> if (++changes > MAX_CHANGES) index++
        }
    }
    return null
}

/** `IMG_1.jpg` → `IMG_1 (2).jpg`; a leading dot is part of the name, not an extension. */
internal fun neighbourName(name: String, index: Int): String {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: return "$name ($index)"
    return "${name.substring(0, dot)} ($index)${name.substring(dot)}"
}

private sealed interface Existing {
    data class Same(val fileId: Long?) : Existing

    data object Different : Existing

    data object Changed : Existing
}

private suspend fun compare(
    dav: FilesDavClient,
    path: DavFilePath,
    remote: DavFileProperties,
    localLength: Long,
    openLocal: () -> InputStream,
): Existing {
    // Sizes settle most different files without downloading them.
    if (localLength >= 0L && remote.size != null && remote.size != localLength) {
        return Existing.Different
    }
    // Pinned to the ETag, so the id read with it belongs to the compared bytes.
    return when (
        val read = dav.readFile(path, remote.etag) { stream ->
            openLocal().use { local -> sameBytes(local, stream) }
        }
    ) {
        is ReadFileResult.Read -> if (read.value) {
            Existing.Same(
                remote.fileId,
            )
        } else {
            Existing.Different
        }

        ReadFileResult.Changed -> Existing.Changed
    }
}

/** Stops at the first difference; a large identical video is still read to its end. */
internal suspend fun sameBytes(first: InputStream, second: InputStream): Boolean {
    val left = first.source().buffer()
    val right = second.source().buffer()
    while (left.request(1)) {
        currentCoroutineContext().ensureActive()
        val chunk = left.readByteString(left.buffer.size)
        if (!right.rangeEquals(0, chunk)) return false
        right.skip(chunk.size.toLong())
    }
    return right.exhausted()
}

/** The original name plus `(2)`…`(100)`. */
private const val MAX_NAMES = 100
private const val MAX_CHANGES = 3

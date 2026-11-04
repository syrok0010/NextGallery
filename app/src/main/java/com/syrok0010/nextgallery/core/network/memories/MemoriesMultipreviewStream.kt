package com.syrok0010.nextgallery.core.network.memories

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.BufferedSource

/** One image of a multipreview response; [reqid] matches [MemoriesMultipreviewFileRequest.reqid]. */
class MemoriesPreviewDto(val reqid: Int, val type: String, val bytes: ByteArray)

/**
 * Reads the next block of the multipreview stream, or null at its end. A block is a one-byte
 * header length, a JSON header and `len` image bytes. Memories writes blocks as previews become
 * ready and silently skips files without a preview. A truncated block fails with EOFException.
 */
internal fun readMemoriesPreview(source: BufferedSource, json: Json): MemoriesPreviewDto? {
    if (source.exhausted()) return null
    val headerLength = source.readByte().toLong() and 0xFF
    val header = json.decodeFromString<PreviewHeader>(source.readUtf8(headerLength))
    require(header.len >= 0) { "Negative multipreview image length" }
    return MemoriesPreviewDto(header.reqid, header.type, source.readByteArray(header.len.toLong()))
}

@Serializable
private data class PreviewHeader(val reqid: Int, val len: Int, val type: String)

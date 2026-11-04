package com.syrok0010.nextgallery.core.network.memories

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.BufferedSource

/** Preview of the file [fileId] from a multipreview response. */
class MemoriesPreviewDto(val fileId: Long, val type: String, val bytes: ByteArray)

@Serializable
internal data class MultipreviewRequest(val files: List<MultipreviewFileRequest>)

/** [a] = "1" keeps the aspect ratio instead of cropping to [x]×[y]. */
@Serializable
internal data class MultipreviewFileRequest(
    val fileid: Long,
    val x: Int,
    val y: Int,
    val a: String,
    val reqid: Int,
)

/** One block of the response; [reqid] matches [MultipreviewFileRequest.reqid]. */
internal class MultipreviewBlock(val reqid: Int, val type: String, val bytes: ByteArray)

/**
 * Reads the next block of the multipreview stream, or null at its end. A block is a one-byte
 * header length, a JSON header and `len` image bytes. Memories writes blocks as previews become
 * ready and silently skips files without a preview. A truncated block fails with EOFException.
 */
internal fun readMultipreviewBlock(source: BufferedSource, json: Json): MultipreviewBlock? {
    if (source.exhausted()) return null
    val headerLength = source.readByte().toLong() and 0xFF
    val header = json.decodeFromString<BlockHeader>(source.readUtf8(headerLength))
    require(header.len >= 0) { "Negative multipreview image length" }
    return MultipreviewBlock(header.reqid, header.type, source.readByteArray(header.len.toLong()))
}

@Serializable
private data class BlockHeader(val reqid: Int, val len: Int, val type: String)

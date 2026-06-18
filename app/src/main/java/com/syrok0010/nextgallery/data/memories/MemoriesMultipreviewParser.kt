package com.syrok0010.nextgallery.data.memories

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class ThumbnailPreview(
    val fileId: Long,
    val requestId: Int,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ThumbnailPreview) return false

        return fileId == other.fileId &&
            requestId == other.requestId &&
            mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = fileId.hashCode()
        result = 31 * result + requestId
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

fun parseMemoriesMultipreviewResponse(
    bytes: ByteArray,
    json: Json,
): List<ThumbnailPreview> {
    val previews = mutableListOf<ThumbnailPreview>()
    var index = 0

    while (index < bytes.size) {
        val jsonLength = bytes[index].toInt() and 0xFF
        index += 1

        require(bytes.size - index >= jsonLength) {
            "Truncated multipreview header"
        }

        val headerJson = bytes.decodeToString(startIndex = index, endIndex = index + jsonLength)
        index += jsonLength

        val header = json.decodeFromString<MemoriesMultipreviewResponseHeader>(headerJson)
        require(header.len >= 0) {
            "Negative multipreview image length"
        }
        require(bytes.size - index >= header.len) {
            "Truncated multipreview image"
        }

        previews += ThumbnailPreview(
            fileId = 0L,
            requestId = header.reqid,
            mimeType = header.type,
            bytes = bytes.copyOfRange(index, index + header.len),
        )
        index += header.len
    }

    return previews
}

@Serializable
private data class MemoriesMultipreviewResponseHeader(
    val reqid: Int,
    val len: Int,
    val type: String,
)

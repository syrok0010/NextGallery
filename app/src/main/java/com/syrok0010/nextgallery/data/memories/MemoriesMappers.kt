package com.syrok0010.nextgallery.data.memories

import java.time.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

fun MemoriesConfigDto.toMemoriesConfig(): MemoriesConfig {
    return MemoriesConfig(
        version = version,
        timelinePath = timelinePath,
        albumsEnabled = albumsEnabled,
        recognizeEnabled = recognizeEnabled,
        faceRecognitionEnabled = faceRecognitionEnabled,
        previewGeneratorEnabled = previewGeneratorEnabled,
        stackRawFiles = stackRawFiles,
        dedupIdentical = dedupIdentical,
    )
}

fun MemoriesPhotoDto.toMediaItem(): MediaItem {
    val isVideoValue = isVideo.asFlexibleBoolean() || mimetype?.startsWith("video/") == true

    return MediaItem(
        fileId = fileid,
        dayId = dayid,
        day = LocalDate.ofEpochDay(dayid.toLong()),
        displayName = basename ?: "file-$fileid",
        mimeType = mimetype,
        width = w,
        height = h,
        etag = etag,
        livePhotoId = liveid,
        auid = auid,
        buid = buid,
        sharedBy = sharedBy,
        takenAtEpochSeconds = epoch,
        isVideo = isVideoValue,
        videoDurationSeconds = videoDuration,
        isFavorite = isFavorite.asFlexibleBoolean(),
        isHidden = isHidden.asFlexibleBoolean(),
        assetRef = MediaAssetRef.MemoriesFile(photoFileId = fileid),
    )
}

private fun JsonElement?.asFlexibleBoolean(): Boolean {
    val primitive = this?.jsonPrimitive ?: return false
    return primitive.booleanOrNull
        ?: primitive.intOrNull?.let { it != 0 }
        ?: primitive.content.equals("true", ignoreCase = true)
        || primitive.content == "1"
}

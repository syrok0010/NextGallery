package com.syrok0010.nextgallery.feature.timeline.remote

import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.network.memories.MemoriesConfigDto
import com.syrok0010.nextgallery.core.network.memories.MemoriesPhotoDto
import com.syrok0010.nextgallery.feature.library.toMemoriesMediaItem
import com.syrok0010.nextgallery.feature.timeline.MemoriesConfig

fun MemoriesConfigDto.toMemoriesConfig(): MemoriesConfig =
    MemoriesConfig(
        version = version,
        timelinePath = timelinePath,
        albumsEnabled = albumsEnabled,
        recognizeEnabled = recognizeEnabled,
        faceRecognitionEnabled = faceRecognitionEnabled,
        previewGeneratorEnabled = previewGeneratorEnabled,
        stackRawFiles = stackRawFiles,
        dedupIdentical = dedupIdentical,
    )

fun MemoriesPhotoDto.toMediaItem(mediaId: MediaId): MediaItem = toMemoriesMediaItem(mediaId)

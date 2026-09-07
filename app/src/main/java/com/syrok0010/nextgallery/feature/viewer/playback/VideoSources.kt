package com.syrok0010.nextgallery.feature.viewer.playback

import com.syrok0010.nextgallery.core.media.MediaAssetRef

/** Logical references only: server URL and credentials are resolved when opening the source. */
internal data class VideoSources(val primary: String, val fallback: String? = null) {
    companion object {
        fun from(asset: MediaAssetRef): VideoSources = when (asset) {
            is MediaAssetRef.LocalContent -> VideoSources(asset.contentUri)
            is MediaAssetRef.MemoriesFile -> VideoSources(remoteUri(asset.photoFileId))
            is MediaAssetRef.LocalFirst -> VideoSources(asset.local.contentUri, remoteUri(asset.remote.photoFileId))
        }

        private fun remoteUri(fileId: Long) = "https://memories.invalid/original/$fileId"
    }
}

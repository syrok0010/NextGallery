package com.syrok0010.nextgallery.feature.viewer.playback

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.feature.images.MemoriesAssetUrlFactory

/** Resolved for a playback session; the media catalogue retains only asset identifiers. */
internal data class VideoSources(
    val primary: String,
    val fallback: String? = null,
    val remoteOriginal: RemoteVideoOriginal? = null,
) {
    companion object {
        fun from(asset: MediaAssetRef, serverUrl: String? = null): VideoSources = when (asset) {
            is MediaAssetRef.LocalContent -> VideoSources(asset.contentUri)
            is MediaAssetRef.MemoriesFile -> {
                val original = RemoteVideoOriginal(asset.photoFileId, checkNotNull(serverUrl))
                VideoSources(original.uri, remoteOriginal = original)
            }
            is MediaAssetRef.LocalFirst -> {
                val original = RemoteVideoOriginal(asset.remote.photoFileId, checkNotNull(serverUrl))
                VideoSources(asset.local.contentUri, original.uri, original)
            }
        }
    }
}

/** File identity is explicit: discovery never extracts it from a URL. */
internal data class RemoteVideoOriginal(val fileId: Long, private val serverUrl: String) {
    val uri: String = MemoriesAssetUrlFactory.urlsFor(MediaAssetRef.MemoriesFile(fileId), serverUrl).originalUrl
    private val api = NextcloudTransport.normalizeServerOrigin(serverUrl) + "/apps/memories/api"
    val configurationUri: String get() = "$api/config"

    fun masterPlaylist(clientId: String): String {
        require(clientId.matches(Regex("[a-zA-Z0-9-]{8,}"))) { "Invalid video client" }
        return "$api/video/transcode/$clientId/$fileId/index.m3u8"
    }
}

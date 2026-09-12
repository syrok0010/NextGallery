package com.syrok0010.nextgallery.feature.viewer.playback

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class RemoteVideoQuality(val label: String, val uri: String)

/** Memories names max.m3u8 Original; Direct is the untouched /stream source. */
@OptIn(UnstableApi::class)
internal object MemoriesVideoManifest {
    fun qualities(masterUri: String, manifest: String): List<RemoteVideoQuality> {
        val playlist = manifest.byteInputStream().use {
            HlsPlaylistParser().parse(masterUri.toUri(), it)
        } as? HlsMultivariantPlaylist ?: return emptyList()
        val base = masterUri.toHttpUrl()
        val variants = playlist.variants.mapNotNull { variant ->
            val uri = variant.url.toString().toHttpUrlOrNull() ?: return@mapNotNull null
            // The VOD contract uses siblings only. Never authenticate arbitrary manifest URLs.
            if (uri.scheme != base.scheme ||
                uri.host != base.host ||
                uri.port != base.port ||
                uri.username.isNotEmpty() ||
                uri.password.isNotEmpty() ||
                uri.pathSegments.dropLast(1) != base.pathSegments.dropLast(1)
            ) return@mapNotNull null
            val resolution = minOf(variant.format.width, variant.format.height)
            val label = if (uri.pathSegments.last() == "max.m3u8") "Original"
            else resolution.takeIf { it > 0 }?.let { "${it}p" } ?: return@mapNotNull null
            RemoteVideoQuality(label, uri.toString())
        }.distinct()
        return if (variants.isEmpty()) emptyList() else listOf(
            RemoteVideoQuality(
                "Auto",
                masterUri
            )
        ) + variants
    }
}

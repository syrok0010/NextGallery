package com.syrok0010.nextgallery.feature.viewer.playback

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class RemoteVideoQuality(val label: String, val uri: String)

/** Memories names max.m3u8 Original; Direct is the untouched /stream source. */
internal object MemoriesVideoManifest {
    fun qualities(masterUri: String, manifest: String): List<RemoteVideoQuality> {
        if (!manifest.trimStart().startsWith("#EXTM3U")) throw IOException("Invalid HLS manifest")
        val base = masterUri.toHttpUrl()
        val variants = mutableListOf<RemoteVideoQuality>()
        var resolution: Int? = null
        var pending = false
        for (line in manifest.lineSequence().map(String::trim)) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = true
                resolution = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)?.let {
                    val width = it.groupValues[1].toIntOrNull()
                    val height = it.groupValues[2].toIntOrNull()
                    if (width != null && height != null) minOf(width, height) else null
                }
            } else if (pending && line.isNotEmpty() && !line.startsWith('#')) {
                pending = false
                val uri = base.resolve(line) ?: continue
                // The VOD contract uses siblings only. Never authenticate arbitrary manifest URLs.
                if (uri.scheme != base.scheme || uri.host != base.host || uri.port != base.port ||
                    uri.pathSegments.dropLast(1) != base.pathSegments.dropLast(1)) continue
                val label = if (uri.pathSegments.last() == "max.m3u8") "Original"
                    else resolution?.takeIf { it > 0 }?.let { "${it}p" } ?: continue
                variants += RemoteVideoQuality(label, uri.toString())
            }
        }
        return if (variants.isEmpty()) emptyList() else listOf(RemoteVideoQuality("Auto", masterUri)) + variants.distinct()
    }
}

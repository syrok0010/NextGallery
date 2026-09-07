package com.syrok0010.nextgallery.feature.viewer

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.syrok0010.nextgallery.feature.viewer.playback.VideoAuthenticationRequired

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun PlaybackException.toVideoPlaybackError(): VideoPlaybackError {
    val causes = generateSequence<Throwable>(this) { it.cause }.toList()
    if (causes.any { it is VideoAuthenticationRequired }) return VideoPlaybackError.AuthenticationRequired
    val response = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    if (response?.responseCode in listOf(401, 403)) return VideoPlaybackError.AuthenticationRequired
    if (causes.any { it is HttpDataSource.HttpDataSourceException }) return VideoPlaybackError.RemoteUnavailable
    return VideoPlaybackError.CannotPlay
}

package com.syrok0010.nextgallery.feature.viewer

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

@OptIn(UnstableApi::class)
internal fun PlaybackException.toVideoPlaybackError(): VideoPlaybackError {
    val causes = generateSequence<Throwable>(this) { it.cause }.toList()
    val response = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    if (response?.responseCode in listOf(401, 403)) return VideoPlaybackError.AuthenticationRequired
    if (causes.any { it is HttpDataSource.HttpDataSourceException }) return VideoPlaybackError.RemoteUnavailable
    return VideoPlaybackError.CannotPlay
}

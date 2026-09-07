package com.syrok0010.nextgallery.feature.viewer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import java.io.InterruptedIOException
import android.content.Context
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState

@OptIn(UnstableApi::class)
internal class VideoPlayerFactory(transport: NextcloudTransport, sessionStore: SessionStore) {
    // Redirects must not carry app credentials to a different server or a login page.
    private val client = transport.baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(AuthenticatedVideoSource(transport) {
            // Logout disposes the viewer; cancel any request racing with that disposal.
            val session = sessionStore.session.value as? SessionUiState.SignedIn
                ?: throw InterruptedIOException("Playback session ended")
            session.credentials
        })
        .build()

    fun create(context: Context): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(
            DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client)),
        ))
        .build()
}

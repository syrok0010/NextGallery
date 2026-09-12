package com.syrok0010.nextgallery.feature.viewer

import androidx.activity.compose.BackHandler
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import org.koin.compose.koinInject

/** One active player shared by the page and filmstrip; adjacent pages own no players. */
internal class ViewerPlaybackState(
    private val create: (MediaItem) -> VideoPlaybackController,
) {
    var current by mutableStateOf<VideoPlaybackController?>(null)
        private set
    private var activeItem: MediaItem? = null

    fun select(item: MediaItem?) {
        val video = item?.takeIf { it.isVideo }
        if (activeItem?.mediaId == video?.mediaId && activeItem?.assetRef == video?.assetRef) return
        close()
        activeItem = video
        if (video != null) current = create(video).also { it.start() }
    }

    fun pause() { current?.dispatch(VideoPlaybackInput.Pause) }

    fun close() {
        current?.close()
        current = null
        activeItem = null
    }
}

@Composable
internal fun rememberViewerPlaybackState(
    item: MediaItem?,
    factory: VideoPlayerFactory = koinInject(),
    createPlayer: (Context) -> ExoPlayer = factory::create,
): ViewerPlaybackState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(context, factory) {
        ViewerPlaybackState { video ->
            VideoPlaybackController(video.mediaId, createPlayer(context), factory.sources(video.assetRef), factory, scope)
        }
    }
    val fullscreen = state.current?.state?.isFullscreen == true
    BackHandler(enabled = fullscreen) { state.current?.dispatch(VideoPlaybackInput.ExitFullscreen) }
    VideoFullscreenEffect(fullscreen)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(state, item?.mediaId, item?.assetRef, item?.isVideo) {
        state.select(item)
        onDispose { state.close() }
    }
    DisposableEffect(state, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

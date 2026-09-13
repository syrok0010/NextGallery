package com.syrok0010.nextgallery.feature.viewer

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.images.MediaAssetImage
import com.syrok0010.nextgallery.feature.images.MediaImagePurpose
import com.syrok0010.nextgallery.feature.viewer.playback.VideoSources
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.koin.compose.koinInject

internal const val VideoPlaybackSurfaceTestTag = "video_playback_surface"
internal const val VideoPlaybackPlayPauseTestTag = "video_playback_play_pause"
internal const val VideoPlaybackControlsPlayPauseTestTag = "video_playback_controls_play_pause"
internal const val VideoPlaybackSeekTestTag = "video_playback_seek"
internal const val VideoPlaybackMuteTestTag = "video_playback_mute"
internal const val VideoPlaybackFullscreenTestTag = "video_playback_fullscreen"

@OptIn(UnstableApi::class)
@Composable
internal fun VideoPlaybackSurface(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onToggleChrome: () -> Unit,
    contentModifier: Modifier = Modifier.fillMaxSize(),
    controlsVisible: Boolean = true,
    onFullscreenChanged: (Boolean) -> Unit = {},
    playerFactory: VideoPlayerFactory = koinInject(),
    createPlayer: (Context) -> ExoPlayer = playerFactory::create,
) {
    val sources = VideoSources.from(item.assetRef)
    val context = LocalContext.current
    val player = remember(item.mediaId, sources) {
        createPlayer(context)
    }
    val session = remember(item.mediaId, sources) {
        VideoPlaybackSession(sources.primary, sources.fallback)
    }
    var playbackState by remember(item.mediaId, sources) {
        mutableStateOf(session.state)
    }
    val scope = rememberCoroutineScope()
    val vodClientId = remember(session) { java.util.UUID.randomUUID().toString() }
    fun discoverQualities() = scope.async(start = CoroutineStart.LAZY) {
        val remote = sources.fallback ?: sources.primary.takeIf { it.startsWith("https://memories.invalid/") }
        if (remote == null) emptyList() else try {
            playerFactory.qualities(remote, vodClientId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.io.IOException) {
            emptyList()
        } catch (_: kotlinx.serialization.SerializationException) {
            emptyList()
        }
    }
    var qualities by remember(session) { mutableStateOf(discoverQualities()) }
    val fullscreenChanged by rememberUpdatedState(onFullscreenChanged)
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    var controlsHeight by remember { mutableStateOf(0.dp) }

    var sourceGeneration by remember(session) { mutableIntStateOf(0) }

    fun applyEffect(effect: VideoPlaybackEffect?) {
        when (effect) {
            is VideoPlaybackEffect.PrepareAndPlay -> {
                sourceGeneration++
                qualities.start()
                player.playWhenReady = effect.playWhenReady
                player.setMediaItem(Media3Item.fromUri(effect.contentUri), effect.positionMillis)
                player.prepare()
                player.playWhenReady = effect.playWhenReady
            }

            VideoPlaybackEffect.Play -> player.play()
            VideoPlaybackEffect.ReplayFromStart -> {
                player.seekTo(0L)
                player.play()
            }
            VideoPlaybackEffect.Pause -> player.pause()
            VideoPlaybackEffect.PauseAndRelease -> {
                player.pause()
                player.release()
            }
            is VideoPlaybackEffect.SeekTo -> player.seekTo(effect.positionMillis)
            is VideoPlaybackEffect.SetVolume -> player.volume = effect.volume
            null -> Unit
        }
    }

    fun dispatch(input: VideoPlaybackInput) {
        if (input == VideoPlaybackInput.Retry ||
            (input == VideoPlaybackInput.Play && session.state.phase == VideoPlaybackPhase.Error)
        ) {
            qualities.cancel()
            qualities = discoverQualities()
        }
        val effect = session.accept(input)
        playbackState = session.state
        applyEffect(effect)
    }

    DisposableEffect(player, session) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_IDLE -> Unit
                    Player.STATE_BUFFERING -> dispatch(VideoPlaybackInput.PlayerBuffering)
                    Player.STATE_READY -> dispatch(
                        VideoPlaybackInput.PlayerReady(
                            player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
                        ),
                    )
                    Player.STATE_ENDED -> {
                        player.duration
                            .takeUnless { it == C.TIME_UNSET }
                            ?.coerceAtLeast(0L)
                            ?.let { dispatch(VideoPlaybackInput.PlayerPositionChanged(it)) }
                        dispatch(VideoPlaybackInput.PlayerIsPlaying(false))
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                dispatch(VideoPlaybackInput.PlayerIsPlaying(isPlaying))
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                dispatch(VideoPlaybackInput.PlayerPositionChanged(player.currentPosition))
                val failure = error.toVideoPlaybackError()
                val uri = player.currentMediaItem?.localConfiguration?.uri.toString()
                if (failure != VideoPlaybackError.CannotPlay || !uri.startsWith("https://memories.invalid/original/")) {
                    dispatch(VideoPlaybackInput.SourceFailed(failure))
                    return
                }
                val generation = sourceGeneration
                dispatch(VideoPlaybackInput.PlayerBuffering)
                scope.launch {
                    dispatch(VideoPlaybackInput.QualitiesLoaded(qualities.await()))
                    if (!player.isReleased && generation == sourceGeneration) {
                        dispatch(VideoPlaybackInput.SourceFailed(failure))
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            qualities.cancel()
            player.removeListener(listener)
            applyEffect(session.accept(VideoPlaybackInput.Leave))
            fullscreenChanged(false)
        }
    }

    LaunchedEffect(session, qualities, playbackState.phase != VideoPlaybackPhase.Poster) {
        if (playbackState.phase != VideoPlaybackPhase.Poster) {
            dispatch(VideoPlaybackInput.QualitiesLoaded(qualities.await()))
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            if (player.playbackState == Player.STATE_READY) {
                dispatch(VideoPlaybackInput.PlayerPositionChanged(player.currentPosition))
            }
            delay(200L.milliseconds)
        }
    }

    DisposableEffect(lifecycleOwner, player, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) dispatch(VideoPlaybackInput.Pause)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = playbackState.isFullscreen) {
        dispatch(VideoPlaybackInput.ExitFullscreen)
    }
    LaunchedEffect(playbackState.isFullscreen) {
        fullscreenChanged(playbackState.isFullscreen)
    }
    VideoFullscreenEffect(playbackState.isFullscreen)

    BoxWithConstraints(
        modifier = modifier
            .testTag(VideoPlaybackSurfaceTestTag),
    ) {
        Box(modifier = contentModifier.align(Alignment.Center).background(Color.Black)) {
            MediaAssetImage(
                item = item,
                purpose = MediaImagePurpose.DetailPreview,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )

            if (playbackState.phase != VideoPlaybackPhase.Poster &&
                playbackState.phase != VideoPlaybackPhase.Error
            ) {
                ContentFrame(
                    player = player,
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onToggleChrome),
        )

        if (playbackState.phase != VideoPlaybackPhase.Playing &&
            (controlsVisible || playbackState.phase in setOf(VideoPlaybackPhase.Poster, VideoPlaybackPhase.Error, VideoPlaybackPhase.Loading))
        ) VideoPlaybackCenterAction(
            phase = playbackState.phase,
            error = playbackState.error,
            onClick = {
                when (playbackState.phase) {
                    VideoPlaybackPhase.Error -> dispatch(VideoPlaybackInput.Retry)
                    VideoPlaybackPhase.Playing -> dispatch(VideoPlaybackInput.Pause)
                    else -> dispatch(VideoPlaybackInput.Play)
                }
            },
            modifier = Modifier.align(Alignment.Center)
                .offset {
                    val y = if (controlsVisible) minOf(0.dp, maxHeight / 2 - controlsHeight - 64.dp) else 0.dp
                    IntOffset(0, y.roundToPx())
                },
        )

        if (controlsVisible) VideoPlaybackControls(
            state = playbackState,
            onPlayPause = {
                if (playbackState.showsPauseAction) {
                    dispatch(VideoPlaybackInput.Pause)
                } else {
                    dispatch(VideoPlaybackInput.Play)
                }
            },
            onSelectQuality = { quality ->
                dispatch(VideoPlaybackInput.PlayerPositionChanged(player.currentPosition))
                dispatch(VideoPlaybackInput.SelectQuality(quality))
            },
            onSeek = { positionMillis -> dispatch(VideoPlaybackInput.SeekTo(positionMillis)) },
            onToggleMute = { dispatch(VideoPlaybackInput.ToggleMute) },
            onToggleFullscreen = {
                dispatch(
                    if (playbackState.isFullscreen) {
                        VideoPlaybackInput.ExitFullscreen
                    } else {
                        VideoPlaybackInput.EnterFullscreen
                    },
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter)
                .onSizeChanged { controlsHeight = with(density) { it.height.toDp() } }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = if (playbackState.isFullscreen) 0.dp else FilmstripRowHeight),
        )
    }
}

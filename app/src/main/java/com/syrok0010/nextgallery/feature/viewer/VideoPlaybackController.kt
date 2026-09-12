package com.syrok0010.nextgallery.feature.viewer

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import com.syrok0010.nextgallery.feature.viewer.playback.VideoSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns one player's commands, callbacks and asynchronous work until the surface leaves composition. */
@OptIn(UnstableApi::class)
internal class VideoPlaybackController(
    val player: ExoPlayer,
    private val sources: VideoSources,
    private val playerFactory: VideoPlayerFactory,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val session = VideoPlaybackSession(sources.primary, sources.fallback)
    var state by mutableStateOf(session.state)
        private set
    private var scrubController: VideoScrubController? = null
    private val vodClientId = java.util.UUID.randomUUID().toString()
    private var sourceGeneration = 0
    private var started = false
    private var closed = false
    private var qualityObserver: Job? = null

    private fun discoverQualities() = scope.async(start = CoroutineStart.LAZY) {
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
    private var qualities = discoverQualities()
    private fun applyEffect(effect: VideoPlaybackEffect?) {
        when (effect) {
            is VideoPlaybackEffect.PrepareAndPlay -> {
                sourceGeneration++
                loadQualities()
                player.playWhenReady = effect.playWhenReady
                player.setMediaItem(Media3Item.fromUri(effect.contentUri), effect.positionMillis)
                player.prepare()
                player.playWhenReady = effect.playWhenReady
            }

            is VideoPlaybackEffect.SilentSeek -> {
                player.pause()
                player.volume = 0f
                if (effect.prepareUri != null) {
                    sourceGeneration++
                    loadQualities()
                    player.setMediaItem(Media3Item.fromUri(effect.prepareUri), effect.positionMillis)
                    player.prepare()
                } else player.seekTo(effect.positionMillis)
            }
            is VideoPlaybackEffect.FinishScrub -> {
                player.volume = effect.volume
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
        if (closed) return
        if (input == VideoPlaybackInput.Retry ||
            (input == VideoPlaybackInput.Play && session.state.phase == VideoPlaybackPhase.Error)
        ) {
            qualityObserver?.cancel()
            qualityObserver = null
            qualities.cancel()
            qualities = discoverQualities()
        }
        val effect = session.accept(input)
        state = session.state
        scrubController?.sourceUri = session.sourceUri
        applyEffect(effect)
    }

    private val listener = object : Player.Listener {
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

    private fun loadQualities() {
        if (qualityObserver?.isActive == true) return
        val request = qualities
        qualityObserver = scope.launch {
            dispatch(VideoPlaybackInput.QualitiesLoaded(request.await()))
        }
    }

    fun bindScrub(controller: VideoScrubController?) {
        scrubController?.dispatch = null
        scrubController = controller
        controller?.sourceUri = session.sourceUri
        controller?.dispatch = ::dispatch
    }

    fun start() {
        check(!closed)
        if (started) return
        started = true
        player.addListener(listener)
        scope.launch {
            while (isActive) {
                if (player.playbackState == Player.STATE_READY && !session.state.isScrubbing) {
                    dispatch(VideoPlaybackInput.PlayerPositionChanged(player.currentPosition))
                }
                delay(200)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        bindScrub(null)
        scope.cancel()
        player.removeListener(listener)
        applyEffect(session.accept(VideoPlaybackInput.Leave))
    }
}

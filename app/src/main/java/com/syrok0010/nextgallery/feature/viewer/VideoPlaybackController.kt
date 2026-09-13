package com.syrok0010.nextgallery.feature.viewer

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.feature.viewer.playback.RemoteVideoQuality
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import com.syrok0010.nextgallery.feature.viewer.playback.VideoSources
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import androidx.media3.common.MediaItem as Media3Item

/** Owns one player's commands, callbacks and asynchronous work until the viewer changes its active media or closes. */
@OptIn(UnstableApi::class)
internal class VideoPlaybackController(
    val mediaId: MediaId,
    val player: ExoPlayer,
    private val sources: VideoSources,
    private val playerFactory: VideoPlayerFactory,
    parentScope: CoroutineScope,
) : FilmstripPlayback {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + Dispatchers.Main.immediate + SupervisorJob(parentScope.coroutineContext[Job]),
    )
    private val session = VideoPlaybackSession(sources.primary, sources.fallback, sources.remoteOriginal?.uri)
    var state by mutableStateOf(session.state)
        private set
    private var sourceUri by mutableStateOf(session.sourceUri)
    private val vodClientId = UUID.randomUUID().toString()
    private var started = false
    private var closed by mutableStateOf(false)
    private var discovery: Job? = null
    private var pendingFailure: VideoPlaybackError? = null

    private fun loadQualities() {
        if (discovery != null) return
        discovery = scope.launch {
            val remote = sources.remoteOriginal
            val result = if (remote == null) emptyList() else try {
                playerFactory.qualities(remote, vodClientId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                emptyList()
            } catch (_: SerializationException) {
                emptyList()
            }
            dispatch(VideoPlaybackInput.QualitiesLoaded(result))
            pendingFailure?.let { failure ->
                pendingFailure = null
                dispatch(VideoPlaybackInput.SourceFailed(failure))
            }
        }
    }

    private fun prepareSource(uri: String, position: Long, playWhenReady: Boolean) {
        pendingFailure = null
        loadQualities()
        player.playWhenReady = playWhenReady
        player.setMediaItem(Media3Item.fromUri(uri), position)
        player.prepare()
    }

    private fun applyEffect(effect: VideoPlaybackEffect?) {
        when (effect) {
            is VideoPlaybackEffect.PrepareSource ->
                prepareSource(effect.contentUri, effect.positionMillis, effect.playWhenReady)
            is VideoPlaybackEffect.SilentSeek -> {
                player.pause()
                player.volume = 0f
                if (effect.prepareUri != null) prepareSource(effect.prepareUri, effect.positionMillis, false)
                else player.seekTo(effect.positionMillis)
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
            discovery?.cancel()
            discovery = null
            pendingFailure = null
        }
        val effect = session.accept(input)
        state = session.state
        sourceUri = session.sourceUri
        applyEffect(effect)
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val phase = when (player.playbackState) {
                Player.STATE_BUFFERING -> VideoPlaybackPhase.Loading
                Player.STATE_READY -> if (player.isPlaying) VideoPlaybackPhase.Playing else VideoPlaybackPhase.Paused
                Player.STATE_ENDED -> VideoPlaybackPhase.Paused
                else -> return // Keep poster, terminal error or pending fallback while Media3 is idle.
            }
            dispatch(VideoPlaybackInput.PlayerChanged(
                phase, player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L),
            ))
            if (!state.isScrubbing) {
                dispatch(VideoPlaybackInput.PlayerPositionChanged(player.currentPosition))
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            dispatch(VideoPlaybackInput.PlayerPositionChanged(player.currentPosition))
            val failure = error.toVideoPlaybackError()
            if (failure != VideoPlaybackError.CannotPlay || !session.isRemoteOriginal) {
                dispatch(VideoPlaybackInput.SourceFailed(failure))
                return
            }
            if (discovery?.isCompleted == true) {
                dispatch(VideoPlaybackInput.SourceFailed(failure))
            } else {
                pendingFailure = failure
                dispatch(VideoPlaybackInput.PlayerChanged(VideoPlaybackPhase.Loading))
                loadQualities()
            }
        }
    }

    fun selectQuality(quality: RemoteVideoQuality) {
        if (closed) return
        dispatch(VideoPlaybackInput.PlayerPositionChanged(player.currentPosition))
        dispatch(VideoPlaybackInput.SelectQuality(quality))
    }

    override fun sourceFor(id: MediaId): String? =
        sourceUri.takeIf { !closed && id == mediaId }

    override fun seek(id: MediaId, position: Long, finished: Boolean) {
        if (id != mediaId) return
        dispatch(VideoPlaybackInput.ScrubTo(position))
        if (finished) finish(id)
    }

    override fun finish(id: MediaId) {
        if (id == mediaId) dispatch(VideoPlaybackInput.EndScrub)
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
        scope.cancel()
        player.removeListener(listener)
        player.pause()
        player.release()
    }
}

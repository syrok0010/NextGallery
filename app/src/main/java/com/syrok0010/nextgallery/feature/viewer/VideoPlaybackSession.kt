package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.feature.viewer.playback.RemoteVideoQuality

internal enum class VideoPlaybackPhase {
    Poster,
    Loading,
    Ready,
    Playing,
    Paused,
    Error,
}

internal enum class VideoPlaybackError {
    CannotPlay,
    AuthenticationRequired,
    RemoteUnavailable,
    TranscodeFailed,
}

internal data class VideoPlaybackState(
    val phase: VideoPlaybackPhase = VideoPlaybackPhase.Poster,
    val positionMillis: Long = 0,
    val durationMillis: Long? = null,
    val playRequested: Boolean = false,
    val isMuted: Boolean = false,
    val error: VideoPlaybackError? = null,
    val isFullscreen: Boolean = false,
    val qualities: List<RemoteVideoQuality> = emptyList(),
    val quality: String = "Direct",
) {
    val showsPauseAction: Boolean
        get() = phase == VideoPlaybackPhase.Playing || (phase == VideoPlaybackPhase.Loading && playRequested)
}

internal sealed interface VideoPlaybackInput {
    data class QualitiesLoaded(val qualities: List<RemoteVideoQuality>) : VideoPlaybackInput

    data class SelectQuality(val quality: RemoteVideoQuality) : VideoPlaybackInput

    data object Play : VideoPlaybackInput

    data object Pause : VideoPlaybackInput

    data class PlayerReady(val durationMillis: Long) : VideoPlaybackInput

    data object PlayerBuffering : VideoPlaybackInput

    data class PlayerIsPlaying(val value: Boolean) : VideoPlaybackInput

    data class SeekTo(val positionMillis: Long) : VideoPlaybackInput

    data object ToggleMute : VideoPlaybackInput

    data object PlayerFailed : VideoPlaybackInput

    data class SourceFailed(val error: VideoPlaybackError) : VideoPlaybackInput

    data object Retry : VideoPlaybackInput

    data object Leave : VideoPlaybackInput

    data class PlayerPositionChanged(val positionMillis: Long) : VideoPlaybackInput

    data object EnterFullscreen : VideoPlaybackInput

    data object ExitFullscreen : VideoPlaybackInput
}

internal sealed interface VideoPlaybackEffect {
    data class PrepareAndPlay(val contentUri: String, val positionMillis: Long = 0, val playWhenReady: Boolean = true) : VideoPlaybackEffect

    data object Play : VideoPlaybackEffect

    data object ReplayFromStart : VideoPlaybackEffect

    data object Pause : VideoPlaybackEffect

    data object PauseAndRelease : VideoPlaybackEffect

    data class SeekTo(val positionMillis: Long) : VideoPlaybackEffect

    data class SetVolume(val volume: Float) : VideoPlaybackEffect
}

internal class VideoPlaybackSession(
    private val contentUri: String,
    private val fallbackUri: String? = null,
) {
    private var usingFallback = false
    private var hlsAttempted = false
    private var currentUri = contentUri
    private val remoteUri = fallbackUri ?: contentUri.takeIf { it.startsWith("https://memories.invalid/original/") }

    var state: VideoPlaybackState = VideoPlaybackState()
        private set

    fun accept(input: VideoPlaybackInput): VideoPlaybackEffect? = when (input) {
        is VideoPlaybackInput.QualitiesLoaded -> {
            state = state.copy(qualities = remoteUri?.let {
                if (input.qualities.isEmpty()) emptyList() else listOf(RemoteVideoQuality("Direct", it)) + input.qualities
            } ?: emptyList())
            null
        }
        is VideoPlaybackInput.SelectQuality -> {
            if (input.quality !in state.qualities) null else {
                hlsAttempted = input.quality.label != "Direct"
                usingFallback = true
                state = state.copy(quality = input.quality.label)
                replaceSource(input.quality.uri)
            }
        }
        VideoPlaybackInput.Play -> {
            when (state.phase) {
                VideoPlaybackPhase.Poster,
                VideoPlaybackPhase.Error,
                -> {
                    prepareAndPlay()
                }

                VideoPlaybackPhase.Ready,
                VideoPlaybackPhase.Paused,
                -> {
                    state = state.copy(playRequested = true)
                    val durationMillis = state.durationMillis
                    if (durationMillis != null && state.positionMillis >= durationMillis) {
                        VideoPlaybackEffect.ReplayFromStart
                    } else {
                        VideoPlaybackEffect.Play
                    }
                }

                VideoPlaybackPhase.Loading -> {
                    if (state.playRequested) null else {
                        state = state.copy(playRequested = true)
                        VideoPlaybackEffect.Play
                    }
                }
                VideoPlaybackPhase.Playing -> null
            }
        }

        VideoPlaybackInput.Pause -> {
            state = state.copy(playRequested = false)
            if (state.phase in setOf(VideoPlaybackPhase.Playing, VideoPlaybackPhase.Ready, VideoPlaybackPhase.Paused)) {
                state = state.copy(phase = VideoPlaybackPhase.Paused)
            }
            VideoPlaybackEffect.Pause
        }

        is VideoPlaybackInput.PlayerReady -> {
            state = state.copy(
                phase = VideoPlaybackPhase.Ready,
                durationMillis = input.durationMillis.coerceAtLeast(0L),
                positionMillis = state.positionMillis.coerceIn(0L, input.durationMillis.coerceAtLeast(0L)),
            )
            null
        }

        VideoPlaybackInput.PlayerBuffering -> {
            state = state.copy(phase = VideoPlaybackPhase.Loading)
            null
        }

        is VideoPlaybackInput.PlayerIsPlaying -> {
            if (state.phase in setOf(
                    VideoPlaybackPhase.Ready,
                    VideoPlaybackPhase.Playing,
                    VideoPlaybackPhase.Paused,
                )
            ) {
                state = state.copy(
                    phase = if (input.value) VideoPlaybackPhase.Playing else VideoPlaybackPhase.Paused,
                )
            }
            null
        }

        is VideoPlaybackInput.SeekTo -> {
            val positionMillis = input.positionMillis.coerceIn(0L, state.durationMillis ?: Long.MAX_VALUE)
            state = state.copy(positionMillis = positionMillis)
            VideoPlaybackEffect.SeekTo(positionMillis)
        }

        VideoPlaybackInput.ToggleMute -> {
            val isMuted = !state.isMuted
            state = state.copy(isMuted = isMuted)
            VideoPlaybackEffect.SetVolume(if (isMuted) 0f else 1f)
        }

        VideoPlaybackInput.PlayerFailed -> sourceFailed(VideoPlaybackError.CannotPlay)
        is VideoPlaybackInput.SourceFailed -> sourceFailed(input.error)

        VideoPlaybackInput.Retry -> if (hlsAttempted) replaceSource(currentUri) else prepareAndPlay()

        VideoPlaybackInput.Leave -> {
            usingFallback = false
            hlsAttempted = false
            currentUri = contentUri
            state = VideoPlaybackState()
            VideoPlaybackEffect.PauseAndRelease
        }

        is VideoPlaybackInput.PlayerPositionChanged -> {
            state = state.copy(
                positionMillis = input.positionMillis.coerceIn(0L, state.durationMillis ?: Long.MAX_VALUE),
            )
            null
        }

        VideoPlaybackInput.EnterFullscreen -> {
            state = state.copy(isFullscreen = true)
            null
        }

        VideoPlaybackInput.ExitFullscreen -> {
            state = state.copy(isFullscreen = false)
            null
        }
    }

    private fun sourceFailed(error: VideoPlaybackError): VideoPlaybackEffect? {
        if (!usingFallback && fallbackUri != null) {
            usingFallback = true
            return replaceSource(fallbackUri)
        }
        if (!hlsAttempted && currentUri == remoteUri && error == VideoPlaybackError.CannotPlay) {
            state.qualities.firstOrNull { it.label == "Auto" }?.let {
                hlsAttempted = true
                state = state.copy(quality = it.label)
                return replaceSource(it.uri)
            }
        }
        state = state.copy(phase = VideoPlaybackPhase.Error,
            error = if (hlsAttempted) VideoPlaybackError.TranscodeFailed else error,
            playRequested = if (hlsAttempted) state.playRequested else false)
        return null
    }

    private fun replaceSource(uri: String): VideoPlaybackEffect {
        currentUri = uri
        state = state.copy(phase = VideoPlaybackPhase.Loading, error = null)
        return VideoPlaybackEffect.PrepareAndPlay(uri, state.positionMillis, state.playRequested)
    }

    private fun prepareAndPlay(): VideoPlaybackEffect {
        if (hlsAttempted) {
            state = state.copy(playRequested = true)
            return replaceSource(currentUri)
        }
        currentUri = contentUri
        usingFallback = false
        state = state.copy(
            phase = VideoPlaybackPhase.Loading,
            error = null,
            playRequested = true,
            durationMillis = null,
            positionMillis = 0,
        )
        return VideoPlaybackEffect.PrepareAndPlay(contentUri)
    }
}

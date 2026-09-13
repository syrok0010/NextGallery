package com.syrok0010.nextgallery.feature.viewer

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
}

internal data class VideoPlaybackState(
    val phase: VideoPlaybackPhase = VideoPlaybackPhase.Poster,
    val positionMillis: Long = 0,
    val durationMillis: Long? = null,
    val playRequested: Boolean = false,
    val isMuted: Boolean = false,
    val error: VideoPlaybackError? = null,
    val isFullscreen: Boolean = false,
) {
    val showsPauseAction: Boolean
        get() = phase == VideoPlaybackPhase.Playing || (phase == VideoPlaybackPhase.Loading && playRequested)
}

internal sealed interface VideoPlaybackInput {
    data object Play : VideoPlaybackInput

    data object Pause : VideoPlaybackInput

    data class PlayerReady(val durationMillis: Long) : VideoPlaybackInput

    data object PlayerBuffering : VideoPlaybackInput

    data class PlayerIsPlaying(val value: Boolean) : VideoPlaybackInput

    data class SeekTo(val positionMillis: Long) : VideoPlaybackInput

    data object ToggleMute : VideoPlaybackInput

    data object PlayerFailed : VideoPlaybackInput

    data object Retry : VideoPlaybackInput

    data object Leave : VideoPlaybackInput

    data class PlayerPositionChanged(val positionMillis: Long) : VideoPlaybackInput

    data object EnterFullscreen : VideoPlaybackInput

    data object ExitFullscreen : VideoPlaybackInput
}

internal sealed interface VideoPlaybackEffect {
    data class PrepareAndPlay(val contentUri: String) : VideoPlaybackEffect

    data object Play : VideoPlaybackEffect

    data object ReplayFromStart : VideoPlaybackEffect

    data object Pause : VideoPlaybackEffect

    data object PauseAndRelease : VideoPlaybackEffect

    data class SeekTo(val positionMillis: Long) : VideoPlaybackEffect

    data class SetVolume(val volume: Float) : VideoPlaybackEffect
}

internal class VideoPlaybackSession(
    private val contentUri: String,
) {
    var state: VideoPlaybackState = VideoPlaybackState()
        private set

    fun accept(input: VideoPlaybackInput): VideoPlaybackEffect? = when (input) {
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

        VideoPlaybackInput.PlayerFailed -> {
            state = state.copy(
                phase = VideoPlaybackPhase.Error,
                error = VideoPlaybackError.CannotPlay,
                playRequested = false,
            )
            null
        }

        VideoPlaybackInput.Retry -> prepareAndPlay()

        VideoPlaybackInput.Leave -> {
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

    private fun prepareAndPlay(): VideoPlaybackEffect {
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

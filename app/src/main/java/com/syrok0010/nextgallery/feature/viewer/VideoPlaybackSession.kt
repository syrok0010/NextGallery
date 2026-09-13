package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.feature.viewer.playback.RemoteVideoQuality

internal enum class VideoPlaybackPhase {
    Poster,
    Loading,
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
    val isScrubbing: Boolean = false,
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

    data class PlayerChanged(
        val phase: VideoPlaybackPhase,
        val durationMillis: Long? = null,
    ) : VideoPlaybackInput

    data class SeekTo(val positionMillis: Long) : VideoPlaybackInput

    data class ScrubTo(val positionMillis: Long) : VideoPlaybackInput

    data object EndScrub : VideoPlaybackInput

    data object ToggleMute : VideoPlaybackInput

    data class SourceFailed(val error: VideoPlaybackError) : VideoPlaybackInput

    data object Retry : VideoPlaybackInput

    data class PlayerPositionChanged(val positionMillis: Long) : VideoPlaybackInput

    data object EnterFullscreen : VideoPlaybackInput

    data object ExitFullscreen : VideoPlaybackInput
}

internal sealed interface VideoPlaybackEffect {
    data class PrepareSource(val contentUri: String, val positionMillis: Long = 0, val playWhenReady: Boolean = true) : VideoPlaybackEffect

    data class SilentSeek(val positionMillis: Long, val prepareUri: String?) : VideoPlaybackEffect

    data class FinishScrub(val playWhenReady: Boolean, val volume: Float) : VideoPlaybackEffect

    data object Play : VideoPlaybackEffect

    data object ReplayFromStart : VideoPlaybackEffect

    data object Pause : VideoPlaybackEffect

    data class SeekTo(val positionMillis: Long) : VideoPlaybackEffect

    data class SetVolume(val volume: Float) : VideoPlaybackEffect
}

internal class VideoPlaybackSession(
    private val contentUri: String,
    private val fallbackUri: String? = null,
    private val remoteUri: String? = fallbackUri,
) {
    private enum class SourceKind { Local, RemoteOriginal, Hls }
    private data class SelectedSource(val uri: String, val kind: SourceKind)
    private fun source(uri: String) = SelectedSource(uri, when (uri) {
        remoteUri -> SourceKind.RemoteOriginal
        contentUri -> SourceKind.Local
        else -> SourceKind.Hls
    })
    private var selectedSource = source(contentUri)
    val sourceUri: String get() = selectedSource.uri
    val isRemoteOriginal: Boolean get() = selectedSource.kind == SourceKind.RemoteOriginal
    private val isHls: Boolean get() = selectedSource.kind == SourceKind.Hls

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
            if (state.phase in setOf(VideoPlaybackPhase.Playing, VideoPlaybackPhase.Paused)) {
                state = state.copy(phase = VideoPlaybackPhase.Paused)
            }
            VideoPlaybackEffect.Pause
        }

        is VideoPlaybackInput.PlayerChanged -> {
            val duration = input.durationMillis?.coerceAtLeast(0L) ?: state.durationMillis
            state = state.copy(
                phase = input.phase,
                durationMillis = duration,
                positionMillis = state.positionMillis.coerceIn(0L, duration ?: Long.MAX_VALUE),
            )
            null
        }

        is VideoPlaybackInput.SeekTo -> {
            val positionMillis = input.positionMillis.coerceIn(0L, state.durationMillis ?: Long.MAX_VALUE)
            state = state.copy(positionMillis = positionMillis)
            VideoPlaybackEffect.SeekTo(positionMillis)
        }

        is VideoPlaybackInput.ScrubTo -> {
            val prepare = state.phase in setOf(VideoPlaybackPhase.Poster, VideoPlaybackPhase.Error)
            val position = input.positionMillis.coerceIn(0, state.durationMillis ?: Long.MAX_VALUE)
            state = state.copy(positionMillis = position, isScrubbing = true, playRequested = false, error = null,
                phase = if (prepare) VideoPlaybackPhase.Loading else state.phase)
            VideoPlaybackEffect.SilentSeek(position, sourceUri.takeIf { prepare })
        }

        VideoPlaybackInput.EndScrub -> {
            if (!state.isScrubbing) null else {
                state = state.copy(isScrubbing = false)
                VideoPlaybackEffect.FinishScrub(state.playRequested, if (state.isMuted) 0f else 1f)
            }
        }

        VideoPlaybackInput.ToggleMute -> {
            val isMuted = !state.isMuted
            state = state.copy(isMuted = isMuted)
            VideoPlaybackEffect.SetVolume(if (isMuted) 0f else 1f)
        }

        is VideoPlaybackInput.SourceFailed -> sourceFailed(input.error)

        VideoPlaybackInput.Retry -> if (isHls) replaceSource(sourceUri) else prepareAndPlay()

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
        if (selectedSource.kind == SourceKind.Local && fallbackUri != null) {
            return replaceSource(fallbackUri)
        }
        if (isRemoteOriginal && error == VideoPlaybackError.CannotPlay) {
            state.qualities.firstOrNull { it.isAdaptive }?.let {
                state = state.copy(quality = it.label)
                return replaceSource(it.uri)
            }
        }
        state = state.copy(phase = VideoPlaybackPhase.Error,
            error = if (isHls && error == VideoPlaybackError.CannotPlay) VideoPlaybackError.TranscodeFailed else error,
            playRequested = if (isHls) state.playRequested else false)
        return null
    }

    private fun replaceSource(uri: String): VideoPlaybackEffect {
        selectedSource = source(uri)
        state = state.copy(phase = VideoPlaybackPhase.Loading, error = null)
        return VideoPlaybackEffect.PrepareSource(uri, state.positionMillis, state.playRequested && !state.isScrubbing)
    }

    private fun prepareAndPlay(): VideoPlaybackEffect {
        if (isHls) {
            state = state.copy(playRequested = true)
            return replaceSource(sourceUri)
        }
        selectedSource = source(contentUri)
        state = state.copy(
            phase = VideoPlaybackPhase.Loading,
            error = null,
            playRequested = true,
            durationMillis = null,
            positionMillis = 0,
        )
        return VideoPlaybackEffect.PrepareSource(contentUri)
    }
}

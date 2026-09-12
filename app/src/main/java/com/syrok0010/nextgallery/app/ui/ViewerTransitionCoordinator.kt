package com.syrok0010.nextgallery.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.session.SessionUiState

internal interface ViewerTransitionCoordinator {
    val viewerMediaId: MediaId?
    val revealMediaId: MediaId?

    fun onSessionChanged(session: SessionUiState)

    fun open(mediaId: MediaId)

    fun close(mediaId: MediaId, isTimelineTargetAvailable: Boolean)

    fun onCurrentItemChanged(mediaId: MediaId, isTimelineTargetAvailable: Boolean)

    fun onTimelineMediaRevealed()

    fun registerTimelineTile(
        mediaId: MediaId,
        boundsProvider: () -> Rect?,
    ): () -> Unit

    fun timelineTileBounds(mediaId: MediaId): Rect?

    fun onAppBoundsChanged(bounds: Rect)
}

@Composable
internal fun rememberViewerTransitionCoordinator(): ViewerTransitionCoordinator {
    return rememberSaveable(saver = DefaultViewerTransitionCoordinator.Saver) {
        DefaultViewerTransitionCoordinator()
    }
}

internal class DefaultViewerTransitionCoordinator(
    initialViewerMediaId: MediaId? = null,
    initialRevealMediaId: MediaId? = null,
    initialCurrentTimelineTargetAvailable: Boolean = false,
) : ViewerTransitionCoordinator {
    override var viewerMediaId: MediaId? by mutableStateOf(initialViewerMediaId)
        private set

    override var revealMediaId: MediaId? by mutableStateOf(initialRevealMediaId)
        private set

    private var appBounds: Rect? = null
    internal var currentTimelineTargetAvailable = initialCurrentTimelineTargetAvailable
        private set
    private val timelineTileBoundsProvidersByMediaId = mutableMapOf<MediaId, () -> Rect?>()

    override fun onSessionChanged(session: SessionUiState) {
        if (session is SessionUiState.SignedIn) {
            return
        }

        viewerMediaId = null
        revealMediaId = null
        appBounds = null
        currentTimelineTargetAvailable = false
        timelineTileBoundsProvidersByMediaId.clear()
    }

    override fun open(mediaId: MediaId) {
        viewerMediaId = mediaId
        revealMediaId = null
        currentTimelineTargetAvailable = true
    }

    override fun close(mediaId: MediaId, isTimelineTargetAvailable: Boolean) {
        viewerMediaId = null
        currentTimelineTargetAvailable = isTimelineTargetAvailable
        syncRevealTarget(mediaId, isTimelineTargetAvailable)
    }

    override fun onCurrentItemChanged(mediaId: MediaId, isTimelineTargetAvailable: Boolean) {
        viewerMediaId = mediaId
        currentTimelineTargetAvailable = isTimelineTargetAvailable
        syncRevealTarget(mediaId, isTimelineTargetAvailable)
    }

    override fun onTimelineMediaRevealed() {
        revealMediaId = null
    }

    override fun registerTimelineTile(
        mediaId: MediaId,
        boundsProvider: () -> Rect?,
    ): () -> Unit {
        timelineTileBoundsProvidersByMediaId[mediaId] = boundsProvider
        return {
            timelineTileBoundsProvidersByMediaId.remove(mediaId, boundsProvider)
        }
    }

    override fun timelineTileBounds(mediaId: MediaId): Rect? {
        return timelineTileBoundsProvidersByMediaId[mediaId]
            ?.invoke()
            ?.takeIf(::isVisibleInAppBounds)
    }

    override fun onAppBoundsChanged(bounds: Rect) {
        appBounds = bounds
        viewerMediaId?.let { mediaId ->
            syncRevealTarget(mediaId, currentTimelineTargetAvailable)
        }
    }

    private fun syncRevealTarget(mediaId: MediaId, isTimelineTargetAvailable: Boolean) {
        revealMediaId = if (!isTimelineTargetAvailable || timelineTileBounds(mediaId) != null) {
            null
        } else {
            mediaId
        }
    }

    private fun isVisibleInAppBounds(tileBounds: Rect): Boolean {
        val rootBounds = appBounds ?: return true
        return tileBounds.overlaps(rootBounds)
    }

    companion object {
        val Saver: Saver<DefaultViewerTransitionCoordinator, Any> = listSaver(
            save = { coordinator ->
                listOf(
                    coordinator.viewerMediaId?.value,
                    coordinator.revealMediaId?.value,
                    coordinator.currentTimelineTargetAvailable,
                )
            },
            restore = { values ->
                DefaultViewerTransitionCoordinator(
                    initialViewerMediaId = (values[0] as? String)?.let(::MediaId),
                    initialRevealMediaId = (values[1] as? String)?.let(::MediaId),
                    initialCurrentTimelineTargetAvailable = (values[2] as? Boolean) ?: false,
                )
            },
        )
    }
}

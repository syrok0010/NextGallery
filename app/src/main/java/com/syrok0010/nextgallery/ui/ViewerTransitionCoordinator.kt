package com.syrok0010.nextgallery.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.syrok0010.nextgallery.domain.media.MediaId

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

internal class DefaultViewerTransitionCoordinator : ViewerTransitionCoordinator {
    override var viewerMediaId: MediaId? by mutableStateOf(null)
        private set

    override var revealMediaId: MediaId? by mutableStateOf(null)
        private set

    private var appBounds: Rect? = null
    private var currentTimelineTargetAvailable = false
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
}

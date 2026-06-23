package com.syrok0010.nextgallery.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

internal interface ViewerTransitionCoordinator {
    val viewerFileId: Long?
    val revealFileId: Long?
    val visibleTimelineTileBoundsByFileId: Map<Long, Rect>

    fun onSessionChanged(session: SessionUiState)

    fun open(fileId: Long)

    fun close(fileId: Long)

    fun onCurrentItemChanged(fileId: Long)

    fun onTimelineFileRevealed()

    fun onTileBoundsChanged(fileId: Long, bounds: Rect?)

    fun onAppBoundsChanged(bounds: Rect)
}

internal class DefaultViewerTransitionCoordinator : ViewerTransitionCoordinator {
    override var viewerFileId: Long? by mutableStateOf(null)
        private set

    override var revealFileId: Long? by mutableStateOf(null)
        private set

    private var appBounds: Rect? by mutableStateOf(null)
    private val timelineTileBoundsByFileId = mutableStateMapOf<Long, Rect>()

    override val visibleTimelineTileBoundsByFileId: Map<Long, Rect>
        get() = timelineTileBoundsByFileId.filterValues(::isVisibleInAppBounds)

    override fun onSessionChanged(session: SessionUiState) {
        if (session is SessionUiState.SignedIn) {
            return
        }

        viewerFileId = null
        revealFileId = null
        appBounds = null
        timelineTileBoundsByFileId.clear()
    }

    override fun open(fileId: Long) {
        viewerFileId = fileId
        revealFileId = null
    }

    override fun close(fileId: Long) {
        viewerFileId = null
        syncRevealTarget(fileId)
    }

    override fun onCurrentItemChanged(fileId: Long) {
        viewerFileId = fileId
        syncRevealTarget(fileId)
    }

    override fun onTimelineFileRevealed() {
        revealFileId = null
    }

    override fun onTileBoundsChanged(fileId: Long, bounds: Rect?) {
        if (bounds == null) {
            timelineTileBoundsByFileId.remove(fileId)
        } else {
            timelineTileBoundsByFileId[fileId] = bounds
        }

        viewerFileId?.let(::syncRevealTarget)
    }

    override fun onAppBoundsChanged(bounds: Rect) {
        appBounds = bounds
        viewerFileId?.let(::syncRevealTarget)
    }

    private fun syncRevealTarget(fileId: Long) {
        revealFileId = if (isTimelineTileVisible(fileId)) {
            null
        } else {
            fileId
        }
    }

    private fun isTimelineTileVisible(fileId: Long): Boolean {
        val tileBounds = timelineTileBoundsByFileId[fileId] ?: return false
        return isVisibleInAppBounds(tileBounds)
    }

    private fun isVisibleInAppBounds(tileBounds: Rect): Boolean {
        val rootBounds = appBounds ?: return true
        return tileBounds.overlaps(rootBounds)
    }
}

package com.syrok0010.nextgallery.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

internal interface ViewerTransitionCoordinator {
    val viewerFileId: Long?
    val revealFileId: Long?

    fun onSessionChanged(session: SessionUiState)

    fun open(fileId: Long)

    fun close(fileId: Long)

    fun onCurrentItemChanged(fileId: Long)

    fun onTimelineFileRevealed()

    fun registerTimelineTile(
        fileId: Long,
        boundsProvider: () -> Rect?,
    ): () -> Unit

    fun timelineTileBounds(fileId: Long): Rect?

    fun onAppBoundsChanged(bounds: Rect)
}

internal class DefaultViewerTransitionCoordinator : ViewerTransitionCoordinator {
    override var viewerFileId: Long? by mutableStateOf(null)
        private set

    override var revealFileId: Long? by mutableStateOf(null)
        private set

    private var appBounds: Rect? = null
    private val timelineTileBoundsProvidersByFileId = mutableMapOf<Long, () -> Rect?>()

    override fun onSessionChanged(session: SessionUiState) {
        if (session is SessionUiState.SignedIn) {
            return
        }

        viewerFileId = null
        revealFileId = null
        appBounds = null
        timelineTileBoundsProvidersByFileId.clear()
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

    override fun registerTimelineTile(
        fileId: Long,
        boundsProvider: () -> Rect?,
    ): () -> Unit {
        timelineTileBoundsProvidersByFileId[fileId] = boundsProvider
        return {
            timelineTileBoundsProvidersByFileId.remove(fileId)
        }
    }

    override fun timelineTileBounds(fileId: Long): Rect? {
        return timelineTileBoundsProvidersByFileId[fileId]
            ?.invoke()
            ?.takeIf(::isVisibleInAppBounds)
    }

    override fun onAppBoundsChanged(bounds: Rect) {
        appBounds = bounds
        viewerFileId?.let(::syncRevealTarget)
    }

    private fun syncRevealTarget(fileId: Long) {
        revealFileId = if (timelineTileBounds(fileId) != null) {
            null
        } else {
            fileId
        }
    }

    private fun isVisibleInAppBounds(tileBounds: Rect): Boolean {
        val rootBounds = appBounds ?: return true
        return tileBounds.overlaps(rootBounds)
    }
}

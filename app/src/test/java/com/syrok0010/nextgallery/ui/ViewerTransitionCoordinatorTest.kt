package com.syrok0010.nextgallery.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerTransitionCoordinatorTest {
    @Test
    fun `open starts viewer and clears stale reveal target`() {
        val coordinator = DefaultViewerTransitionCoordinator()

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(fileId = 2L) { offscreenTileBounds }
        coordinator.onCurrentItemChanged(2L)

        coordinator.open(1L)

        assertEquals(1L, coordinator.viewerFileId)
        assertNull(coordinator.revealFileId)
    }

    @Test
    fun `current item change schedules reveal for offscreen tile`() {
        val coordinator = DefaultViewerTransitionCoordinator()

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(fileId = 2L) { offscreenTileBounds }

        coordinator.onCurrentItemChanged(2L)

        assertEquals(2L, coordinator.viewerFileId)
        assertEquals(2L, coordinator.revealFileId)
    }

    @Test
    fun `current item change clears pending reveal when item becomes visible`() {
        val coordinator = DefaultViewerTransitionCoordinator()

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(fileId = 1L) { visibleTileBounds }
        coordinator.registerTimelineTile(fileId = 2L) { offscreenTileBounds }
        coordinator.onCurrentItemChanged(2L)

        coordinator.onCurrentItemChanged(1L)

        assertEquals(1L, coordinator.viewerFileId)
        assertNull(coordinator.revealFileId)
    }

    @Test
    fun `close keeps return target for offscreen current item`() {
        val coordinator = DefaultViewerTransitionCoordinator()

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(fileId = 2L) { offscreenTileBounds }
        coordinator.onCurrentItemChanged(2L)

        coordinator.close(2L)

        assertNull(coordinator.viewerFileId)
        assertEquals(2L, coordinator.revealFileId)
    }

    @Test
    fun `tile bounds are queried from provider on demand`() {
        val coordinator = DefaultViewerTransitionCoordinator()
        var currentBounds = visibleTileBounds

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(fileId = 1L) { currentBounds }

        assertEquals(visibleTileBounds, coordinator.timelineTileBounds(1L))

        currentBounds = offscreenTileBounds

        assertNull(coordinator.timelineTileBounds(1L))
    }

    @Test
    fun `stale unregister callback does not remove newer tile provider`() {
        val coordinator = DefaultViewerTransitionCoordinator()
        val unregisterOldProvider = coordinator.registerTimelineTile(fileId = 1L) {
            offscreenTileBounds
        }
        val unregisterCurrentProvider = coordinator.registerTimelineTile(fileId = 1L) {
            visibleTileBounds
        }

        unregisterOldProvider()

        assertEquals(visibleTileBounds, coordinator.timelineTileBounds(1L))

        unregisterCurrentProvider()

        assertNull(coordinator.timelineTileBounds(1L))
    }

    @Test
    fun `signed out session resets viewer transition state`() {
        val coordinator = DefaultViewerTransitionCoordinator()

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(fileId = 1L) { visibleTileBounds }
        coordinator.open(1L)

        coordinator.onSessionChanged(SessionUiState.SignedOut)

        assertNull(coordinator.viewerFileId)
        assertNull(coordinator.revealFileId)
        assertNull(coordinator.timelineTileBounds(1L))
    }

    companion object {
        private val appBounds = Rect(left = 0f, top = 0f, right = 100f, bottom = 100f)
        private val visibleTileBounds = Rect(left = 8f, top = 8f, right = 48f, bottom = 48f)
        private val offscreenTileBounds = Rect(left = 8f, top = 140f, right = 48f, bottom = 180f)
    }
}

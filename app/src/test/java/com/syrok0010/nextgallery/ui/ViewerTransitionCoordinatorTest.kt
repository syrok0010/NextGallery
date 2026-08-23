package com.syrok0010.nextgallery.ui

import androidx.compose.ui.geometry.Rect
import com.syrok0010.nextgallery.domain.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerTransitionCoordinatorTest {
    @Test
    fun `open starts viewer and clears stale reveal target`() {
        val coordinator = DefaultViewerTransitionCoordinator()

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(secondMediaId) { offscreenTileBounds }
        coordinator.onCurrentItemChanged(secondMediaId, isTimelineTargetAvailable = true)

        coordinator.open(firstMediaId)

        assertEquals(firstMediaId, coordinator.viewerMediaId)
        assertNull(coordinator.revealMediaId)
    }

    @Test
    fun `current item change schedules reveal for offscreen tile`() {
        val coordinator = DefaultViewerTransitionCoordinator()

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(secondMediaId) { offscreenTileBounds }

        coordinator.onCurrentItemChanged(secondMediaId, isTimelineTargetAvailable = true)

        assertEquals(secondMediaId, coordinator.viewerMediaId)
        assertEquals(secondMediaId, coordinator.revealMediaId)
    }

    @Test
    fun `current item change clears pending reveal when item becomes visible`() {
        val coordinator = DefaultViewerTransitionCoordinator()

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(firstMediaId) { visibleTileBounds }
        coordinator.registerTimelineTile(secondMediaId) { offscreenTileBounds }
        coordinator.onCurrentItemChanged(secondMediaId, isTimelineTargetAvailable = true)

        coordinator.onCurrentItemChanged(firstMediaId, isTimelineTargetAvailable = true)

        assertEquals(firstMediaId, coordinator.viewerMediaId)
        assertNull(coordinator.revealMediaId)
    }

    @Test
    fun `close keeps return target for offscreen current item`() {
        val coordinator = DefaultViewerTransitionCoordinator()

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(secondMediaId) { offscreenTileBounds }
        coordinator.onCurrentItemChanged(secondMediaId, isTimelineTargetAvailable = true)

        coordinator.close(secondMediaId, isTimelineTargetAvailable = true)

        assertNull(coordinator.viewerMediaId)
        assertEquals(secondMediaId, coordinator.revealMediaId)
    }

    @Test
    fun `tile bounds are queried from provider on demand`() {
        val coordinator = DefaultViewerTransitionCoordinator()
        var currentBounds = visibleTileBounds

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(firstMediaId) { currentBounds }

        assertEquals(visibleTileBounds, coordinator.timelineTileBounds(firstMediaId))

        currentBounds = offscreenTileBounds

        assertNull(coordinator.timelineTileBounds(firstMediaId))
    }

    @Test
    fun `orphan current does not leave a reveal target`() {
        val coordinator = DefaultViewerTransitionCoordinator()

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.onCurrentItemChanged(firstMediaId, isTimelineTargetAvailable = false)

        coordinator.close(firstMediaId, isTimelineTargetAvailable = false)

        assertNull(coordinator.viewerMediaId)
        assertNull(coordinator.revealMediaId)
    }

    @Test
    fun `stale unregister callback does not remove newer tile provider`() {
        val coordinator = DefaultViewerTransitionCoordinator()
        val unregisterOldProvider = coordinator.registerTimelineTile(firstMediaId) {
            offscreenTileBounds
        }
        val unregisterCurrentProvider = coordinator.registerTimelineTile(firstMediaId) {
            visibleTileBounds
        }

        unregisterOldProvider()

        assertEquals(visibleTileBounds, coordinator.timelineTileBounds(firstMediaId))

        unregisterCurrentProvider()

        assertNull(coordinator.timelineTileBounds(firstMediaId))
    }

    @Test
    fun `signed out session resets viewer transition state`() {
        val coordinator = DefaultViewerTransitionCoordinator()

        coordinator.onAppBoundsChanged(appBounds)
        coordinator.registerTimelineTile(firstMediaId) { visibleTileBounds }
        coordinator.open(firstMediaId)

        coordinator.onSessionChanged(SessionUiState.SignedOut)

        assertNull(coordinator.viewerMediaId)
        assertNull(coordinator.revealMediaId)
        assertNull(coordinator.timelineTileBounds(firstMediaId))
    }

    companion object {
        private val appBounds = Rect(left = 0f, top = 0f, right = 100f, bottom = 100f)
        private val visibleTileBounds = Rect(left = 8f, top = 8f, right = 48f, bottom = 48f)
        private val offscreenTileBounds = Rect(left = 8f, top = 140f, right = 48f, bottom = 180f)
        private val firstMediaId = MediaId("media-first")
        private val secondMediaId = MediaId("media-second")
    }
}

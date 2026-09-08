package com.syrok0010.nextgallery.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.domain.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerStateRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun currentPhotoSurvivesRestorationAndLogoutClearsRestoredState() {
        val restoration = StateRestorationTester(composeRule)
        lateinit var coordinator: ViewerTransitionCoordinator
        restoration.setContent {
            val current = rememberViewerTransitionCoordinator()
            SideEffect { coordinator = current }
        }
        val photo = MediaId("restored-photo")
        lateinit var original: ViewerTransitionCoordinator
        composeRule.runOnIdle {
            original = coordinator
            coordinator.onAppBoundsChanged(Rect(0f, 0f, 100f, 100f))
            coordinator.registerTimelineTile(photo) { Rect(0f, 200f, 100f, 300f) }
            coordinator.open(MediaId("first-photo"))
            coordinator.onCurrentItemChanged(photo, isTimelineTargetAvailable = true)
        }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertNotSame(original, coordinator)
            assertEquals(photo, coordinator.viewerMediaId)
            assertEquals(photo, coordinator.revealMediaId)
            assertNull(coordinator.timelineTileBounds(photo))
            coordinator.onSessionChanged(SessionUiState.SignedOut)
        }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            assertNull(coordinator.viewerMediaId)
            assertNull(coordinator.revealMediaId)
        }
    }
}

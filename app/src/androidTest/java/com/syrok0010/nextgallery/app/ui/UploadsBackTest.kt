package com.syrok0010.nextgallery.app.ui

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.AppLinks
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.string
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class UploadsBackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun uploadsFollowTheBackGesture() {
        val sessions = GlobalContext.get().get<SessionStore>()
        val previousSession = sessions.session.value
        rule.runOnUiThread {
            // Nothing listens on the port: the screens only need a session, not a server.
            sessions.signIn(
                AccountCredentials("http://127.0.0.1:9", "fixture", "password", "fixture"),
            )
        }
        try {
            rule.setContent { NextGalleryApp(links = flowOf(AppLinks.Uploads)) }
            val title = string(R.string.uploads_title)
            rule.waitUntil(
                10_000,
            ) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            rule.waitForIdle()
            val resting = rule.onNodeWithText(title).getBoundsInRoot()

            rule.mainClock.autoAdvance = false
            val back = rule.activity.onBackPressedDispatcher
            rule.runOnUiThread {
                back.dispatchOnBackStarted(backEvent(0f))
                back.dispatchOnBackProgressed(backEvent(0.5f))
            }
            rule.mainClock.advanceTimeBy(500)
            val dragged = rule.onNodeWithText(title).getBoundsInRoot()
            val photoNodes = rule.onAllNodesWithText(string(R.string.library_photos))
            val photos = photoNodes.fetchSemanticsNodes().indices.map {
                photoNodes[it]
                    .getBoundsInRoot()
            }
            rule.runOnUiThread { back.dispatchOnBackCancelled() }
            rule.mainClock.autoAdvance = true

            assertTrue(
                "resting=$resting dragged=$dragged",
                dragged.left > resting.left,
            )
            // Photos fills the strip that the dragged Uploads uncovers on the left.
            assertTrue(
                "photos=$photos dragged=$dragged",
                photos.any { it.right > 0.dp && it.left < dragged.left },
            )
        } finally {
            rule.runOnUiThread {
                when (previousSession) {
                    is SessionUiState.SignedIn -> sessions.signIn(previousSession.credentials)
                    else -> sessions.signOut()
                }
            }
        }
    }

    private fun backEvent(progress: Float) =
        BackEventCompat(0f, 1200f, progress, BackEventCompat.EDGE_LEFT)
}

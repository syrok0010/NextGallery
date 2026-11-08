package com.syrok0010.nextgallery.feature.upload

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.app.ui.UploadsScreen
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class UploadsRetryUiTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "uploads-retry-ui-test.db"
    private val account = AccountCredentials("http://test.syrok", "test", "fixture", "test")
    private val id = MediaId("ui-local")
    private lateinit var db: UploadQueueDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var queue: UploadQueue
    private var linked = false
    private var puts = 0

    @Before fun setup() {
        context.deleteDatabase(dbName)
        db = UploadQueueDatabase.open(context, dbName)
        val sessions = SessionStore(object : CredentialsStore {
            override fun load() = account
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        })
        val library = MediaLibraryIndex().apply {
            publish(listOf(MediaItem(
                id, 1, "retry.jpg", "image/jpeg", null, null, null, null, null, null, null,
                1, false, null, false, false,
                MediaAssetRef.LocalContent("content://media/external/images/media/42", 1, 10),
            )))
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        queue = UploadQueue(
            sessions, library, scope, UploadJournal(db),
            { SourceStamp(10, 1, 1) },
            { step, _, _, _, _ -> step != FollowUp.Link || linked },
            {}, UUID.randomUUID().toString(),
        )
    }

    @After fun cleanup() {
        runBlocking {
        scope.cancel()
        db.close()
        context.deleteDatabase(dbName)
        }
    }

    @Test fun retryAfterLinkFailureDoesNotPutAgain() = runBlocking {
        queue.submit(listOf(id))
        queue.drain { account, job, _ ->
            puts++
            UploadedFile(job.selection, DavFilePath.of(account.userId, "/Photos/retry.jpg"), true, 42)
        }
        rule.setContent { MaterialTheme { UploadsScreen({}, queue) } }
        rule.onNodeWithText("Повторить действие").assertIsDisplayed()
        linked = true
        rule.onNodeWithText("Повторить действие").performClick()
        withTimeout(5_000) { while (queue.waiting.value.isEmpty()) delay(10) }
        queue.drain { _, _, _ -> error("receipt retry must not PUT") }
        rule.waitUntil(5_000) { queue.entries.value.single().retryable.not() }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithContentDescription("Загружен").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Загружен").assertIsDisplayed()
        rule.onAllNodesWithText("Повторить действие").assertCountEquals(0)
        assertEquals(1, puts)
    }
}

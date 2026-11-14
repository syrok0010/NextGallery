package com.syrok0010.nextgallery.feature.upload

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.app.ui.UploadsScreen
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.string
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The uploads screen sorts files by what is left to do and offers actions for each section. */
class UploadsScreenSectionsTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "uploads-screen-sections-test.db"
    private val account = AccountCredentials("http://test.syrok", "test", "fixture", "test")
    private val files = listOf(UPLOADED, REFUSED, OFFLINE)
    private val restarts = AtomicInteger()
    private lateinit var db: UploadQueueDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var queue: UploadQueue

    @Before fun setup() {
        context.deleteDatabase(dbName)
        db = UploadQueueDatabase.open(context, dbName)
        val sessions = SessionStore(
            object : CredentialsStore {
                override fun load() = account

                override fun save(credentials: AccountCredentials) = Unit
            },
        )
        val library = MediaLibraryIndex().apply { publish(files.mapIndexed(::media)) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        queue = UploadQueue(
            sessions = sessions,
            library = library,
            scope = scope,
            journal = UploadJournal(db),
            snapshot = { SourceStamp(10, 1, 1) },
            followUps = UploadFollowUps(
                link = { _, _, _, _ -> true },
                album = { _, _, _, _ -> true },
            ),
            refreshLibrary = {},
            restart = { restarts.incrementAndGet() },
        )
    }

    @After fun cleanup() =
        runBlocking {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
            context.deleteDatabase(dbName)
            Unit
        }

    @Test fun clearLeavesFilesThatNeedAttentionAndRetryNowShowsOnlyWhilePaused() =
        runBlocking {
            queue.submit(files.map(::MediaId))
            rule.setContent { MaterialTheme { UploadsScreen({}, queue) } }

            // Queued files simply wait for the worker: nothing to hurry.
            rule.waitUntil(5_000) { visible(R.string.uploads_section_waiting) }
            noRetryNow()

            queue.drain { account, job, _ ->
                when (job.selection.displayName) {
                    "$UPLOADED.jpg" -> UploadedFile(
                        job.selection,
                        DavFilePath.of(account.userId, "/Photos/$UPLOADED.jpg"),
                        true,
                        42,
                    )

                    "$REFUSED.jpg" -> throw UploadException(UploadProblem.Http, 403)

                    else -> throw IOException("offline")
                }
            }

            rule.waitUntil(5_000) {
                visible(
                    R.string.uploads_section_attention,
                ) &&
                    visible(R.string.uploads_section_done)
            }
            section(R.string.uploads_section_waiting).assertIsDisplayed()
            rule
                .onNodeWithContentDescription(
                    string(R.string.uploads_retry_now),
                ).assertIsDisplayed()

            rule.onNodeWithText(string(R.string.uploads_clear)).performClick()

            rule.waitUntil(5_000) { !visible(R.string.uploads_section_done) }
            rule.onAllNodesWithText("$UPLOADED.jpg").assertCountEquals(0)
            section(R.string.uploads_section_attention).assertIsDisplayed()
            rule.onNodeWithText("$REFUSED.jpg").assertIsDisplayed()

            rule.onNodeWithContentDescription(string(R.string.uploads_retry_now)).performClick()

            rule.waitUntil(5_000) { restarts.get() == 1 }
            rule.waitUntil(5_000) { retryNowCount() == 0 }
            section(R.string.uploads_section_waiting).assertIsDisplayed()
            assertEquals(1, restarts.get())
        }

    private fun section(title: Int) = rule.onNodeWithText(string(title), substring = true)

    private fun visible(title: Int) =
        rule.onAllNodesWithText(string(title), substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun retryNowCount() =
        rule
            .onAllNodesWithContentDescription(string(R.string.uploads_retry_now))
            .fetchSemanticsNodes()
            .size

    private fun noRetryNow() = assertEquals(0, retryNowCount())

    private fun media(index: Int, name: String) =
        MediaItem(
            MediaId(name), 1, "$name.jpg", "image/jpeg", null, null, null, null, null, null, null,
            1, false, null, false, false,
            MediaAssetRef.LocalContent("content://media/external/images/media/${index + 1}", 1, 10),
        )

    private companion object {
        const val UPLOADED = "uploaded"
        const val REFUSED = "refused"
        const val OFFLINE = "offline"
    }
}

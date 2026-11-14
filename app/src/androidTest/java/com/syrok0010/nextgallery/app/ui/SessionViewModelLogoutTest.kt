package com.syrok0010.nextgallery.app.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.upload.EnqueueResult
import com.syrok0010.nextgallery.feature.upload.LogoutRefusal
import com.syrok0010.nextgallery.feature.upload.SourceStamp
import com.syrok0010.nextgallery.feature.upload.UploadFollowUps
import com.syrok0010.nextgallery.feature.upload.UploadJournal
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import com.syrok0010.nextgallery.feature.upload.UploadQueueDatabase
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Log out" from the screen down to the app data wipe, over a real upload journal.
 * The wipe itself ends the process, so a fake [AppDataEraser] stands in for it.
 */
class SessionViewModelLogoutTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "session-logout-test.db"
    private val credentials = AccountCredentials("http://test.syrok", "test", "fixture", "test")
    private val sessions = SessionStore(
        object : CredentialsStore {
            override fun load() = credentials

            override fun save(credentials: AccountCredentials) = Unit
        },
    )
    private val photo = MediaId("local-photo")
    private val library = MediaLibraryIndex().apply { publish(listOf(media(photo))) }
    private val erasures = AtomicInteger()
    private var eraseFails = false
    private lateinit var db: UploadQueueDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var queue: UploadQueue
    private lateinit var viewModel: SessionViewModel

    @Before fun setup() {
        context.deleteDatabase(dbName)
        db = UploadQueueDatabase.open(context, dbName)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        queue = UploadQueue(
            sessions = sessions,
            library = library,
            scope = scope,
            journal = UploadJournal(db),
            snapshot = { SourceStamp(5, 10, 20) },
            followUps = UploadFollowUps(
                link = { _, _, _, _ -> true },
                album = { _, _, _, _ -> true },
            ),
            refreshLibrary = {},
        )
        viewModel = SessionViewModel(sessions, queue) {
            erasures.incrementAndGet()
            if (eraseFails) throw IOException("The system did not erase the app data")
        }
    }

    @After fun cleanup() =
        runBlocking {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
            context.deleteDatabase(dbName)
            Unit
        }

    @Test fun logoutWithoutUnfinishedUploadsErasesTheAppData() =
        runBlocking {
            viewModel.logout()

            withTimeout(5_000) { while (erasures.get() == 0) delay(10) }
            assertEquals(1, erasures.get())
            assertNull(viewModel.logoutRefusal.value)
        }

    @Test fun unfinishedUploadKeepsTheDataAndSaysWhy() =
        runBlocking {
            // No worker runs here, so the accepted upload stays waiting.
            assertTrue(queue.submit(listOf(photo)) is EnqueueResult.Accepted)

            viewModel.logout()

            assertEquals(LogoutRefusal.UnfinishedWork, refusal())
            assertEquals(0, erasures.get())
            assertTrue(sessions.session.value is SessionUiState.SignedIn)
        }

    @Test fun failedWipeIsReportedAsAFailedLogoutNotAsUploads() =
        runBlocking {
            eraseFails = true

            viewModel.logout()

            assertEquals(LogoutRefusal.Failed, refusal())
            assertEquals(1, erasures.get())
            assertTrue(sessions.session.value is SessionUiState.SignedIn)
        }

    private suspend fun refusal() =
        withTimeout(5_000) { viewModel.logoutRefusal.first { it != null } }

    private fun media(id: MediaId) =
        MediaItem(
            id, 1, "${id.value}.jpg", "image/jpeg", null, null, null, null, null, null, null,
            1, false, null, false, false,
            MediaAssetRef.LocalContent(
                "content://media/external/images/media/1",
                1,
                1,
            ),
        )
}

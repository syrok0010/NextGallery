package com.syrok0010.nextgallery.feature.upload

import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.media.*
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.DavHttpException
import java.io.IOException
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** The queue over a real on-disk journal; [reopen] stands for a new process. */
class UploadQueueRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "runtime-upload-test.db"
    private val credentials = AccountCredentials("http://test.syrok", "test", "fixture", "test")
    private lateinit var db: UploadQueueDatabase
    private lateinit var journal: UploadJournal
    private lateinit var scope: CoroutineScope
    private lateinit var sessions: SessionStore
    private lateinit var queue: UploadQueue
    private val library = MediaLibraryIndex()
    private var stamp: SourceStamp? = SourceStamp(5, 10, 20)
    private var links = 0
    private val albums = mutableListOf<String>()
    private val albumRecovered = mutableListOf<Boolean>()
    private var transfers = 0
    private var albumAdds = true
    private var schedules = 0
    private var restarts = 0
    private val ids = listOf(MediaId("local-a"), MediaId("local-b"))
    private var processId = ""

    @Before fun setup() {
        context.deleteDatabase(dbName)
        library.publish(ids.map(::media))
        open()
    }

    private fun open() {
        db = UploadQueueDatabase.open(context, dbName)
        journal = UploadJournal(db)
        sessions = SessionStore(object : CredentialsStore {
            override fun load() = credentials
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        processId = UUID.randomUUID().toString()
        queue = UploadQueue(
            sessions, library, scope, journal,
            { stamp ?: throw SecurityException() },
            UploadFollowUps(
                link = { _, _, _, _ -> links++; true },
                album = { _, job, _, recovered -> albums += job.album!!.name; albumRecovered += recovered; albumAdds },
            ),
            {}, processId, { schedules++ }, { restarts++ },
        )
    }

    @After fun cleanup() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        context.deleteDatabase(dbName)
        Unit
    }

    private suspend fun reopen() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        open()
    }

    private suspend fun enqueue(ids: List<MediaId> = this.ids, album: String? = null): List<String> {
        assertTrue(queue.submit(ids, album?.let { UploadAlbum("test", it) }) is EnqueueResult.Accepted)
        return journal.jobs().takeLast(ids.size).map { it.id }
    }

    private suspend fun transfer(account: AccountCredentials, job: UploadJob, onProgress: (Int?) -> Unit): UploadedFile {
        transfers++
        val path = queue.pin(job, DavFilePath(account.userId, listOf("Photos", job.selection.displayName)))
        return UploadedFile(job.selection, path, true, 42)
    }

    private suspend fun waitFor(predicate: suspend () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }

    /** Runs [block] and waits for every coroutine it launched in the queue's scope, such as a command. */
    private suspend fun settled(block: suspend () -> Unit) {
        val job = scope.coroutineContext[Job]!!
        val before = job.children.toSet()
        block()
        withTimeout(5_000) { (job.children.toSet() - before).forEach { it.join() } }
    }

    /** Runnable jobs nobody holds, read from the journal. */
    private suspend fun waiting() = journal.jobs().count { it.waiting }

    private fun phases() = queue.entries.value.map { it.phase }

    /** A job claimed by a process that died; a reopened queue releases it. */
    private suspend fun deadClaim() = journal.claimNext("dead-process")!!

    @Test fun cancellationOfOneFileAndOfAllSurvivesReopen() = runBlocking {
        val (first) = enqueue()
        queue.cancel(first)
        waitFor { phases() == listOf(UploadPhase.Cancelled, UploadPhase.Waiting) }
        reopen()
        waitFor { phases() == listOf(UploadPhase.Cancelled, UploadPhase.Waiting) }
        queue.cancelAll()
        waitFor { phases().all { it == UploadPhase.Cancelled } }
        var loggedOut = false
        assertNull(queue.logout { loggedOut = true; sessions.signOut() })
        assertTrue(loggedOut)
    }

    @Test fun failingToClearTheSessionIsNotReportedAsUnfinishedUploads() = runBlocking {
        enqueue()
        queue.cancelAll()
        waitFor { phases() == ids.map { UploadPhase.Cancelled } }

        assertEquals(LogoutRefusal.Failed, queue.logout { throw IOException("credentials not cleared") })
        assertTrue(sessions.session.value is SessionUiState.SignedIn)
    }

    @Test fun cancelCommittedBeforeTheResponseFencesAcceptAndEveryFollowUp() = runBlocking {
        enqueue(album = "slice1")
        val response = CompletableDeferred<Unit>()
        val sending = CompletableDeferred<Unit>()
        val runner = async {
            queue.drain { account, job, progress ->
                val file = transfer(account, job, progress)
                sending.complete(Unit)
                // The response arrives although the user cancelled meanwhile.
                withContext(NonCancellable) { response.await() }
                file
            }
        }
        sending.await()
        queue.cancelAll()
        waitFor { phases().all { it == UploadPhase.Cancelled } }
        response.complete(Unit)
        runner.await()
        assertEquals(1, transfers)
        assertEquals(0, links)
        assertTrue(albums.isEmpty())
        reopen()
        waitFor { queue.entries.value.size == 2 }
        assertEquals(0, waiting())
        assertTrue(phases().all { it == UploadPhase.Cancelled })
    }

    @Test fun savedReceiptRunsOnlyTheStepsEvenWhenTheSourceDisappeared() = runBlocking {
        enqueue(listOf(ids.first()))
        journal.accept(deadClaim(), UploadReceipt(listOf("Photos", "a.jpg"), true, 42))
        stamp = null
        reopen()
        waitFor { waiting() > 0 }
        queue.drain { _, _, _ -> error("A receipt must not trigger PUT or source access") }
        assertEquals(0, transfers)
        assertEquals(1, links)
        assertEquals(listOf(UploadPhase.Uploaded), phases())
    }

    @Test fun interruptedTransferReusesThePinnedNameAfterReopen() = runBlocking {
        enqueue(listOf(ids.first()))
        val fixed = listOf("Photos", "original.jpg")
        journal.pin(deadClaim(), fixed)
        reopen()
        waitFor { waiting() > 0 }
        queue.drain { account, job, _ ->
            assertEquals(fixed, job.destination)
            transfers++
            UploadedFile(job.selection, DavFilePath(account.userId, job.destination!!), false, 42)
        }
        assertEquals(1, transfers)
        assertEquals(1, links)
        waitFor { phases() == listOf(UploadPhase.Existing) }
    }

    @Test fun changedOriginalNeedsAttentionAndBlocksLogoutUntilCancel() = runBlocking {
        enqueue()
        stamp = stamp!!.copy(generation = 21)
        queue.drain(::transfer)
        assertEquals(0, transfers)
        waitFor { phases().all { it == UploadPhase.NeedsAttention } }
        assertEquals(LogoutRefusal.UnfinishedWork, queue.logout { error("Unfinished jobs must block logout") })
        settled { queue.clearFinished() }
        // Clearing hides finished rows; both jobs that need attention stay visible.
        assertEquals(2, journal.jobs().count { !it.hidden })
        queue.cancelAll()
        waitFor { phases().all { it == UploadPhase.Cancelled } }
        assertNull(queue.logout { sessions.signOut() })
    }

    @Test fun albumStepAfterReopenIsRecoveredAndNeverPutsAgain() = runBlocking {
        enqueue(listOf(ids.first()), album = "slice1")
        journal.accept(deadClaim(), UploadReceipt(listOf("Photos", "a.jpg"), true, 42))
        reopen()
        waitFor { waiting() > 0 }
        queue.drain { _, _, _ -> error("A receipt must not trigger PUT") }
        assertEquals(listOf(true), albumRecovered)
        assertEquals(0, transfers)
        waitFor { queue.entries.value.single().let { it.problem == null && !it.cancellable } }
    }

    @Test fun failedAlbumStepNeedsAttentionAndRetryChecksMembership() = runBlocking {
        enqueue(listOf(ids.first()), album = "slice1")
        albumAdds = false
        queue.drain(::transfer)
        waitFor { queue.entries.value.single().retryable }
        assertEquals(LogoutRefusal.UnfinishedWork, queue.logout { error("The album step is unresolved") })
        albumAdds = true
        val scheduled = schedules
        queue.retry(queue.entries.value.single().id)
        waitFor { waiting() > 0 && schedules == scheduled + 1 }
        queue.drain { _, _, _ -> error("A receipt must not trigger PUT") }
        assertEquals(listOf(false, true), albumRecovered)
        assertEquals(1, transfers)
        waitFor { queue.entries.value.single().let { it.problem == null && !it.cancellable } }
    }

    @Test fun temporaryFailureWaitsForTheNextAttemptAndAPermanentOneNeedsAttention() = runBlocking {
        val (first, second) = enqueue()
        val retry = queue.drain { _, job, _ ->
            transfers++
            when (job.id) {
                first -> throw IOException("offline")
                else -> throw UploadException(UploadProblem.Http, 403)
            }
        }
        assertTrue(retry)
        assertEquals("A deferred job is not taken again in the same pass", 2, transfers)
        val jobs = journal.jobs().associateBy { it.id }
        assertEquals(TransferState.Uncertain, jobs.getValue(first).state)
        assertNull(jobs.getValue(first).problem)
        assertEquals(TransferState.Attention, jobs.getValue(second).state)
        // The server still holds the lock of a PUT interrupted by process death.
        assertTrue(queue.drain { _, _, _ -> throw UploadException(UploadProblem.Http, 423) })
        assertEquals(TransferState.Uncertain, journal.jobs().first { it.id == first }.state)
        assertFalse(queue.drain(::transfer))
        waitFor { phases() == listOf(UploadPhase.Uploaded, UploadPhase.NeedsAttention) }
    }

    @Test fun serverErrorInAFollowUpDefersOnlyThatStep() = runBlocking {
        enqueue(listOf(ids.first()), album = "slice1")
        var albumCalls = 0
        queue = UploadQueue(
            sessions, library, scope, journal, { stamp!! },
            UploadFollowUps(
                link = { _, _, _, _ -> links++; true },
                album = { _, _, _, recovered ->
                    albumRecovered += recovered
                    if (albumCalls++ == 0) throw DavHttpException(503, "COPY")
                    true
                },
            ),
            {}, UUID.randomUUID().toString(),
        )
        assertTrue(queue.drain(::transfer))
        assertEquals(StepState.Pending, journal.jobs().single().steps[FollowUp.Album])
        assertFalse(queue.drain { _, _, _ -> error("No second PUT") })
        assertEquals(listOf(false, true), albumRecovered)
        assertEquals(1, transfers)
        assertEquals(1, links)
    }

    @Test fun aClaimAnEarlierPassHereCouldNotReleaseRunsInTheNextPass() = runBlocking {
        enqueue(listOf(ids.first()))
        journal.claimNext(processId)!!
        assertFalse(queue.drain(::transfer))
        assertEquals(1, transfers)
        assertFalse(journal.jobs().single().unfinished)
    }

    @Test fun aPassStartedWhileAStoppedOneStillCleansUpWaitsForIt() = runBlocking {
        enqueue(listOf(ids.first()))
        val response = CompletableDeferred<Unit>()
        val sending = CompletableDeferred<Unit>()
        var first = true
        val put: suspend (AccountCredentials, UploadJob, (Int?) -> Unit) -> UploadedFile? = { account, job, progress ->
            val file = transfer(account, job, progress)
            if (first) {
                first = false
                sending.complete(Unit)
                // The stopped worker's request is still on the wire.
                withContext(NonCancellable) { response.await() }
            }
            file
        }
        val stopped = launch(Dispatchers.Default) { queue.drain(put) }
        sending.await()
        stopped.cancel()
        val next = async(Dispatchers.Default) { queue.drain(put) }
        try {
            // A pass that does not wait would start its transfer within this window; one that
            // waits is blocked until the response below, so this can only miss a bug, never flake.
            delay(200)
            assertEquals("The next pass waits for the stopped one", 1, transfers)
        } finally {
            response.complete(Unit)
        }
        stopped.join()
        assertFalse(next.await())
        assertEquals(2, transfers)
        assertFalse("The next pass keeps its claim", journal.jobs().single().unfinished)
    }

    @Test fun acceptingAndReopeningScheduleTheWorkerButADeferralLeavesItToBackoff() = runBlocking {
        enqueue(listOf(ids.first()))
        assertTrue("Scheduled before submit returns", schedules > 0)
        // The start-up check may have seen the first job as well; it is done once the rows show.
        waitFor { queue.entries.value.isNotEmpty() }
        val base = schedules
        enqueue(listOf(ids.last()))
        assertEquals(base + 1, schedules)
        reopen()
        waitFor { schedules == base + 2 }
        settled {
            assertTrue(queue.drain { _, _, _ -> throw IOException("offline") })
            queue.drain(::transfer)
        }
        assertEquals("A deferral and finishing schedule nothing", base + 2, schedules)
    }

    @Test fun aDeferralPausesTheQueueUntilTheUserRetriesNow() = runBlocking {
        enqueue(listOf(ids.first()))
        waitFor { queue.entries.value.isNotEmpty() }
        assertFalse("Nothing has failed yet", queue.paused.value)
        assertTrue(queue.drain { _, _, _ -> throw IOException("offline") })
        waitFor { queue.paused.value }

        queue.retryNow()
        waitFor { restarts == 1 }
        assertFalse(queue.paused.value)
        settled { queue.retryNow() }
        assertEquals("No second restart while the first one is pending", 1, restarts)

        // The early pass succeeds; nothing is left to retry.
        assertFalse(queue.drain(::transfer))
        assertFalse(queue.paused.value)
    }

    @Test fun batchQueuedDuringAPassExtendsItAndHistoryStartsANewOne() = runBlocking {
        enqueue(listOf(ids.first()))
        queue.drain(::transfer)
        waitFor { queue.activity.value == null }
        enqueue(listOf(ids.last()))
        waitFor { queue.activity.value?.total == 1 }
        enqueue(listOf(ids.first()))
        waitFor { queue.activity.value?.total == 2 }
        assertEquals(0, queue.activity.value!!.processed)
    }

    @Test fun aFileThatRunsAgainStartsWithoutItsEarlierPercent() = runBlocking {
        val (id) = enqueue(listOf(ids.first()))
        assertTrue(queue.drain { _, _, progress -> progress(100); throw IOException("offline") })
        var shown: Int? = -1
        queue.drain { account, job, progress ->
            waitFor { queue.entries.value.single().phase == UploadPhase.Uploading }
            shown = queue.entries.value.single { it.id == id }.percent
            transfer(account, job, progress)
        }
        assertNull("The earlier attempt's percent must not show", shown)
        assertNull(queue.entries.value.single().percent)
    }

    @Test fun finishedAndCancelledFilesDoNotKeepProgress() = runBlocking {
        val (first, second) = enqueue()
        val (third) = enqueue(listOf(ids.first()))
        queue.drain { account, job, progress ->
            when (job.id) {
                first -> progress(100)
                second -> withContext(NonCancellable) {
                    // A finished file must not stay current.
                    waitFor { queue.activity.value!!.let { it.processed == 1 && it.current == null } }
                    progress(25)
                    queue.consumeResult()
                    waitFor { queue.result.value == null }
                    waitFor { queue.activity.value!!.percent == 25 }
                    assertEquals(job.selection.identityUri, queue.activity.value!!.current)
                    queue.cancel(job.id)
                    waitFor { phases()[1] == UploadPhase.Cancelled }
                    assertNull("A cancelled file must not stay current", queue.activity.value!!.current)
                    assertNull(queue.activity.value!!.percent)
                }
                third -> waitFor { queue.activity.value!!.processed == 2 }
            }
            transfer(account, job, progress)
        }
        assertEquals(listOf(UploadPhase.Uploaded, UploadPhase.Cancelled, UploadPhase.Uploaded), phases())
        assertEquals(2, links)
    }

    @Test fun newAlbumStepRunsOnceAndAnUnstartedOneRunsAfterReopen() = runBlocking {
        enqueue(listOf(ids.first()), album = "slice1")
        queue.drain(::transfer)
        assertEquals(listOf("slice1"), albums)
        enqueue(listOf(ids.last()), album = "slice2")
        journal.accept(deadClaim(), UploadReceipt(listOf("a"), true, 42))
        reopen()
        waitFor { waiting() > 0 }
        queue.drain { _, _, _ -> error("No transfer for a saved receipt") }
        assertEquals(listOf("slice1", "slice2"), albums)
        assertTrue(queue.entries.value.all { it.problem == null })
    }

    @Test fun stoppedExecutorLeavesTheTransferUncertainAndItRunsAgain() = runBlocking {
        enqueue()
        val sending = CompletableDeferred<Unit>()
        val runner = launch {
            queue.drain { _, _, _ -> sending.complete(Unit); awaitCancellation() }
        }
        sending.await()
        runner.cancelAndJoin()
        assertEquals(listOf(TransferState.Uncertain, TransferState.Pending), journal.jobs().map { it.state })
        waitFor { waiting() == 2 }
        reopen()
        waitFor { waiting() == 2 }
        queue.drain(::transfer)
        assertEquals(2, transfers)
        waitFor { phases().all { it == UploadPhase.Uploaded } }
    }

    @Test fun viewingAProblemConsumesOnlyTheSummaryAndCancellationPublishesANewResult() = runBlocking {
        enqueue(listOf(ids.first()))
        stamp = null
        queue.drain(::transfer)
        waitFor { queue.result.value?.problems?.size == 1 }
        queue.consumeResult()
        waitFor { queue.result.value == null }
        assertEquals(listOf(UploadPhase.NeedsAttention), phases())
        assertEquals(LogoutRefusal.UnfinishedWork, queue.logout { error("Viewing a result does not finish the job") })
        reopen()
        waitFor { queue.entries.value.isNotEmpty() }
        assertNull(queue.result.value)
        queue.cancelAll()
        waitFor { queue.result.value?.cancelled == 1 }
        assertNull(queue.logout { sessions.signOut() })
    }

    @Test fun emptySelectionIsNotAStorageFailureAndARejectedJournalPublishesNothing() = runBlocking {
        assertEquals(EnqueueResult.NothingToUpload, queue.submit(emptyList()))
        enqueue(listOf(ids.first()))
        // The queue has started observing the journal, so closing it fails only the next write.
        waitFor { queue.entries.value.isNotEmpty() }
        db.close()
        assertEquals(EnqueueResult.Rejected, queue.submit(ids))
        assertEquals(1, queue.entries.value.size)
    }

    private fun media(id: MediaId) = MediaItem(id, 1, "${id.value}.jpg", "image/jpeg", null, null, null, null, null, null, null,
        1, false, null, false, false, MediaAssetRef.LocalContent("content://media/external/images/media/${if (id == ids.first()) 1 else 2}", 1, 1))
}

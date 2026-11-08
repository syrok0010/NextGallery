package com.syrok0010.nextgallery.feature.upload

import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.media.*
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
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
    private val owner = credentials.uploadOwner
    private lateinit var db: UploadQueueDatabase
    private lateinit var journal: UploadJournal
    private lateinit var scope: CoroutineScope
    private lateinit var sessions: SessionStore
    private lateinit var queue: UploadQueue
    private val library = MediaLibraryIndex()
    private var stamp: SourceStamp? = SourceStamp(5, 10, 20)
    private var links = 0
    private val albums = mutableListOf<String>()
    private val albumModes = mutableListOf<AlbumStepMode>()
    private var transfers = 0
    private val ids = listOf(MediaId("local-a"), MediaId("local-b"))

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
        queue = UploadQueue(
            sessions, library, scope, journal,
            { stamp ?: throw SecurityException() },
            { _, _, _ -> links++; true },
            { album, _, mode -> albums += album.name; albumModes += mode; true },
            {}, UUID.randomUUID().toString(),
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
        return journal.jobs(owner).takeLast(ids.size).map { it.id }
    }

    private suspend fun transfer(account: AccountCredentials, job: UploadJob, onProgress: (Int?) -> Unit): UploadedFile {
        transfers++
        val path = queue.pin(job, DavFilePath(account.userId, listOf("Photos", job.selection.displayName)))
        return UploadedFile(job.selection, path, true, 42)
    }

    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }

    private fun phases() = queue.entries.value.map { it.phase }

    /** A job claimed by a process that died; a reopened queue releases it. */
    private suspend fun deadClaim() = journal.claimNext(owner, "dead-process")!!

    @Test fun cancellationOfOneFileAndOfAllSurvivesReopen() = runBlocking {
        val (first) = enqueue()
        queue.cancel(first)
        waitFor { phases() == listOf(UploadPhase.Cancelled, UploadPhase.Waiting) }
        reopen()
        waitFor { phases() == listOf(UploadPhase.Cancelled, UploadPhase.Waiting) }
        queue.cancelAll()
        waitFor { phases().all { it == UploadPhase.Cancelled } }
        var loggedOut = false
        assertTrue(queue.logout { loggedOut = true; sessions.signOut() })
        assertTrue(loggedOut)
        assertTrue(journal.jobs(owner).isEmpty())
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
        assertTrue(queue.waiting.value.isEmpty())
        assertTrue(phases().all { it == UploadPhase.Cancelled })
    }

    @Test fun savedReceiptRunsOnlyTheStepsEvenWhenTheSourceDisappeared() = runBlocking {
        enqueue(listOf(ids.first()))
        journal.accept(deadClaim(), UploadReceipt(listOf("Photos", "a.jpg"), true, 42))
        stamp = null
        reopen()
        waitFor { queue.waiting.value.isNotEmpty() }
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
        waitFor { queue.waiting.value.isNotEmpty() }
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
        assertFalse(queue.logout { error("Unfinished jobs must block logout") })
        queue.clearFinished()
        delay(50)
        assertEquals(2, queue.entries.value.size)
        queue.cancelAll()
        waitFor { phases().all { it == UploadPhase.Cancelled } }
        assertTrue(queue.logout { sessions.signOut() })
    }

    @Test fun startedAlbumCopyNeedsAttentionAfterReopenAndCanBeAbandoned() = runBlocking {
        enqueue(listOf(ids.first()), album = "slice1")
        val accepted = journal.accept(deadClaim(), UploadReceipt(listOf("a"), true, 42))!!
        journal.album(accepted, StepState.Running)
        reopen()
        waitFor { queue.entries.value.singleOrNull()?.problem != null }
        queue.drain { _, _, _ -> error("No second PUT") }
        assertTrue(albums.isEmpty())
        val entry = queue.entries.value.single()
        assertEquals(UploadPhase.Uploaded, entry.phase)
        assertTrue(entry.cancellable)
        assertFalse(queue.logout { error("The album step is unresolved") })
        queue.cancel(entry.id)
        waitFor { !queue.entries.value.single().cancellable }
        assertEquals(UploadPhase.Uploaded, queue.entries.value.single().phase)
        assertTrue(queue.logout { sessions.signOut() })
    }

    @Test fun recoveredAlbumUsesMembershipCheckModeAndNeverPutsAgain() = runBlocking {
        enqueue(listOf(ids.first()), album = "slice1")
        val claim = journal.claimNext(owner, "dead-process")!!
        val accepted = journal.accept(claim, UploadReceipt(listOf("Photos", "a.jpg"), true, 42))!!
        journal.album(accepted, StepState.Running)
        reopen()
        val id = journal.jobs(owner).single().id
        queue.retry(id)
        waitFor { queue.waiting.value.isNotEmpty() }
        queue.drain { _, _, _ -> error("A receipt must not trigger PUT") }
        assertEquals(listOf(AlbumStepMode.Reconcile), albumModes)
        assertEquals(0, transfers)
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
        waitFor { queue.waiting.value.isNotEmpty() }
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
        assertEquals(listOf(TransferState.Uncertain, TransferState.Pending), journal.jobs(owner).map { it.state })
        waitFor { queue.waiting.value.size == 2 }
        reopen()
        waitFor { queue.waiting.value.size == 2 }
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
        assertFalse(queue.logout { error("Viewing a result does not finish the job") })
        reopen()
        waitFor { queue.entries.value.isNotEmpty() }
        assertNull(queue.result.value)
        queue.cancelAll()
        waitFor { queue.result.value?.cancelled == 1 }
        assertTrue(queue.logout { sessions.signOut() })
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

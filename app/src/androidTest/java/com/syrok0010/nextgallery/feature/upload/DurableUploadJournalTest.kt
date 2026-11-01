package com.syrok0010.nextgallery.feature.upload

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Real on-disk Room, tested through the production journal protocol, never DAO mutations. */
class DurableUploadJournalTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "durable-upload-protocol-test.db"
    private lateinit var db: UploadQueueDatabase
    private lateinit var journal: UploadJournal
    private val owner = "owner-A"
    private val file =
        UploadSelection(
            "content://media/external_primary/images/media/42",
            "slice1.jpg",
            "image/jpeg",
            stamp = SourceStamp(123, 1, 2),
        )
    private val receipt =
        UploadReceipt("http://test.syrok/remote.php/dav/files/test/Photos/slice1.jpg", true, 42)

    @Before fun open() =
        runBlocking {
            context.deleteDatabase(name)
            db = UploadQueueDatabase.open(context, name)
            journal = UploadJournal(db)
            journal.activate(owner)
        }

    @After fun close() {
        db.close()
        context.deleteDatabase(name)
    }
    private suspend fun enqueue(request: String = "request-1"): UploadJob {
        assertTrue(
            journal.enqueue(
                owner,
                request,
                listOf(file),
                listOf(UploadIntent("album", owner = "test", target = "task-album")),
            ) is EnqueueResult.Accepted,
        )
        return journal.jobs(owner).last()
    }
    private suspend fun reopen() {
        db.close()
        db = UploadQueueDatabase.open(context, name)
        journal = UploadJournal(db)
        journal.activate(owner)
        journal.recover(owner, "process-2")
    }

    @Test fun cancelBeforeAcceptSurvivesReopenAndSuppressesEveryFollowUp() =
        runBlocking {
            val queued = enqueue()
            val claim = journal.claim(queued.id, owner, "process-1")!!
            journal.cancel(owner, setOf(queued.id))
            assertNull(journal.accept(claim, receipt))
            reopen()
            val restored = journal.jobs(owner).single()
            assertEquals("Cancelled", restored.state)
            assertNull(restored.receipt)
            assertFalse(restored.runnable)
            assertTrue(journal.canLogout(owner))
        }

    @Test fun acceptIsSingleCasAndReceiptIsIrreversible() =
        runBlocking {
            val claim = journal.claim(enqueue().id, owner, "process-1")!!
            assertNotNull(journal.accept(claim, receipt))
            assertNull(
                "one claim may accept only once",
                journal.accept(claim, receipt.copy(fileId = 999)),
            )
            journal.cancel(owner)
            reopen()
            val restored = journal.jobs(owner).single()
            assertEquals(receipt, restored.receipt)
            assertEquals("Accepted", restored.state)
            assertEquals("Suppressed", restored.linkStep)
            assertEquals("Suppressed", restored.albumStep)
            assertTrue(journal.canLogout(owner))
        }

    @Test fun interruptedPutKeepsDestinationAndFencesTheOldExecutor() =
        runBlocking {
            val claim = journal.claim(enqueue().id, owner, "process-1")!!
            journal.destination(claim, receipt.url)
            assertNull(journal.claim(claim.id, owner, "other-runner"))
            reopen()
            val uncertain = journal.jobs(owner).single()
            assertEquals("Uncertain", uncertain.state)
            assertEquals(receipt.url, uncertain.destination)
            assertNull(journal.accept(claim, receipt))
            val resumed = journal.claim(claim.id, owner, "process-2")!!
            assertEquals(
                receipt.url,
                journal.destination(resumed, "http://wrong/new/path")!!.destination,
            )
            assertNotNull(journal.accept(resumed, receipt.copy(created = false)))
        }

    @Test fun receiptReopensForStepsWithoutNewTransferAndAlbumRunningNeedsAttention() =
        runBlocking {
            val claim = journal.claim(enqueue().id, owner, "process-1")!!
            val accepted = journal.accept(claim, receipt)!!
            journal.step(accepted, "album", StepState.Running)
            reopen()
            val restored = journal.jobs(owner).single()
            assertEquals(receipt, restored.receipt)
            assertEquals("Attention", restored.albumStep)
            assertFalse(journal.canLogout(owner))
            journal.clearFinished(owner)
            assertFalse(journal.jobs(owner).single().hidden)
            journal.cancel(owner)
            assertTrue(journal.canLogout(owner))
        }

    @Test fun duplicateRequestAndIdentityRolesSurviveReopen() =
        runBlocking {
            val first = enqueue()
            reopen()
            val again = journal.enqueue(
                owner,
                first.requestId,
                listOf(file),
                emptyList(),
            ) as EnqueueResult.Accepted
            assertEquals(first.batchId, again.batchId)
            assertEquals(1, journal.jobs(owner).size)
            val restored = journal.jobs(owner).single().selection
            assertEquals(file.contentUri, restored.contentUri)
            assertEquals("content://media/external/images/media/42", restored.identityUri)
            assertEquals(file.stamp, restored.stamp)
        }

    @Test fun rejectedStorageCommitDoesNotAcknowledgeEnqueue() =
        runBlocking {
            db.close()
            assertEquals(
                EnqueueResult.Rejected,
                journal.enqueue(owner, "reject", listOf(file), emptyList()),
            )
        }

    @Test fun stopAfterStepCommitReleasesFinishedClaimWithoutChangingAcceptedResults() =
        runBlocking {
            journal.enqueue(owner, "finish-before-stop", listOf(file), listOf(UploadIntent("link")))
            val queued = journal.jobs(owner).single()
            val accepted = journal.accept(journal.claim(queued.id, owner, "process-1")!!, receipt)!!
            val running = journal.beginStep(accepted, "link")!!
            journal.finishStep(running, "link", UploadStepResult("Done", fileId = 42))!!
            journal.interrupt(owner, setOf(queued.batchId), "process-1")
            val stopped = journal.jobs(owner).single()
            assertNull(
                "A finished step claim must not keep queue activity alive after stop",
                stopped.executor,
            )
            assertEquals(receipt, stopped.receipt)
            assertEquals("Done", stopped.linkStep)
            assertFalse(stopped.unfinished)
        }

    @Test fun newSessionCannotAcceptOldResultEvenForSameServerUid() =
        runBlocking {
            val claim = journal.claim(enqueue().id, owner, "process-1")!!
            journal.activate("owner-B")
            assertNull(journal.accept(claim, receipt))
            assertTrue(journal.jobs("owner-B").isEmpty())
        }
}

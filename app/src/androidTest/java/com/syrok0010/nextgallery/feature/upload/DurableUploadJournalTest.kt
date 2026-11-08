package com.syrok0010.nextgallery.feature.upload

import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.media.MediaAssetRef
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
            MediaAssetRef.LocalContent("content://media/external_primary/images/media/42", 1, 123),
            "slice1.jpg",
            "image/jpeg",
            stamp = SourceStamp(123, 1, 2),
        )
    private val receipt =
        UploadReceipt(listOf("Photos", "slice1.jpg"), true, 42)

    @Before fun open() {
        context.deleteDatabase(name)
        db = UploadQueueDatabase.open(context, name)
        journal = UploadJournal(db)
    }

    @After fun close() {
        db.close()
        context.deleteDatabase(name)
    }

    private suspend fun enqueue(): UploadJob {
        assertTrue(
            journal.enqueue(
                owner,
                listOf(file),
                UploadAlbum("test", "task-album"),
            ) is EnqueueResult.Accepted,
        )
        return journal.jobs(owner).last()
    }

    /** A new process: claims of the previous one are released. */
    private suspend fun reopen() {
        db.close()
        db = UploadQueueDatabase.open(context, name)
        journal = UploadJournal(db)
        journal.release(owner) { it != "process-2" }
    }

    @Test fun cancelBeforeAcceptSurvivesReopenAndSuppressesEveryFollowUp() =
        runBlocking {
            enqueue()
            val claim = journal.claimNext(owner, "process-1")!!
            journal.cancel(owner, setOf(claim.id))
            assertNull(journal.accept(claim, receipt))
            reopen()
            val restored = journal.jobs(owner).single()
            assertEquals(TransferState.Cancelled, restored.state)
            assertNull(restored.receipt)
            assertFalse(restored.runnable)
            assertTrue(journal.clearIfFinished(owner))
        }

    @Test fun cancelAfterAcceptKeepsTheReceipt() =
        runBlocking {
            enqueue()
            assertNotNull(journal.accept(journal.claimNext(owner, "process-1")!!, receipt))
            journal.cancel(owner)
            reopen()
            val restored = journal.jobs(owner).single()
            assertEquals(receipt, restored.receipt)
            assertEquals(TransferState.Accepted, restored.state)
            assertEquals(StepState.Suppressed, restored.linkStep)
            assertEquals(StepState.Suppressed, restored.albumStep)
            assertTrue(journal.clearIfFinished(owner))
        }

    @Test fun interruptedPutKeepsDestinationAndFencesTheOldExecutor() =
        runBlocking {
            enqueue()
            val claim = journal.claimNext(owner, "process-1")!!
            journal.pin(claim, receipt.path)
            assertNull(journal.claimNext(owner, "other-runner"))
            reopen()
            val uncertain = journal.jobs(owner).single()
            assertEquals(TransferState.Uncertain, uncertain.state)
            assertEquals(receipt.path, uncertain.destination)
            assertNull(journal.accept(claim, receipt))
            val resumed = journal.claimNext(owner, "process-2")!!
            assertEquals(
                receipt.path,
                journal.pin(resumed, listOf("wrong", "new", "path"))!!.destination,
            )
            assertNotNull(journal.accept(resumed, receipt.copy(created = false)))
        }

    @Test fun albumStepThatNeverStartedRunsAfterReopen() =
        runBlocking {
            enqueue()
            journal.accept(journal.claimNext(owner, "process-1")!!, receipt)!!
            reopen()
            val restored = journal.jobs(owner).single()
            assertEquals(StepState.Pending, restored.albumStep)
            assertTrue(restored.runnable)
            assertNotNull(journal.claimNext(owner, "process-2"))
        }

    @Test fun startedAlbumCopyNeedsAttentionAfterReopen() =
        runBlocking {
            enqueue()
            val accepted = journal.accept(journal.claimNext(owner, "process-1")!!, receipt)!!
            journal.album(accepted, StepState.Running)
            reopen()
            val restored = journal.jobs(owner).single()
            assertEquals(receipt, restored.receipt)
            assertEquals(StepState.Attention, restored.albumStep)
            assertEquals(UploadProblem.Album, restored.problem)
            assertFalse(journal.clearIfFinished(owner))
            journal.clearFinished(owner)
            assertFalse(journal.jobs(owner).single().hidden)
            journal.cancel(owner)
            assertTrue(journal.clearIfFinished(owner))
        }

    @Test fun selectionSurvivesReopen() =
        runBlocking {
            enqueue()
            reopen()
            val restored = journal.jobs(owner).single()
            assertEquals(file, restored.selection)
            assertEquals("content://media/external/images/media/42", restored.selection.identityUri)
            assertEquals(UploadAlbum("test", "task-album"), restored.album)
        }

    @Test fun rejectedStorageCommitDoesNotAcknowledgeEnqueue() =
        runBlocking {
            // Room opens lazily; a database never opened would open again on the next write.
            journal.jobs(owner)
            db.close()
            assertEquals(EnqueueResult.Rejected, journal.enqueue(owner, listOf(file), null))
        }
}

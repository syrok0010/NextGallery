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
                listOf(file),
                UploadAlbum("test", "task-album"),
            ) is EnqueueResult.Accepted,
        )
        return journal.jobs().last()
    }

    /** A new process: claims of the previous one are released. */
    private suspend fun reopen() {
        db.close()
        db = UploadQueueDatabase.open(context, name)
        journal = UploadJournal(db)
        journal.release { it != "process-2" }
    }

    @Test fun cancelBeforeAcceptSurvivesReopenAndSuppressesEveryFollowUp() =
        runBlocking {
            enqueue()
            val claim = journal.claimNext("process-1")!!
            journal.cancel(setOf(claim.id))
            assertNull(journal.accept(claim, receipt))
            reopen()
            val restored = journal.jobs().single()
            assertEquals(TransferState.Cancelled, restored.state)
            assertNull(restored.receipt)
            assertFalse(restored.runnable)
            assertFalse(journal.hasUnfinished())
        }

    @Test fun cancelAfterAcceptKeepsTheReceipt() =
        runBlocking {
            enqueue()
            assertNotNull(journal.accept(journal.claimNext("process-1")!!, receipt))
            journal.cancel()
            reopen()
            val restored = journal.jobs().single()
            assertEquals(receipt, restored.receipt)
            assertEquals(TransferState.Accepted, restored.state)
            assertEquals(
                mapOf(
                    FollowUp.Link to StepState.Suppressed,
                    FollowUp.Album to StepState.Suppressed,
                ),
                restored.steps,
            )
            assertFalse(journal.hasUnfinished())
        }

    @Test fun interruptedPutKeepsDestinationAndFencesTheOldExecutor() =
        runBlocking {
            enqueue()
            val claim = journal.claimNext("process-1")!!
            journal.pin(claim, receipt.path)
            assertNull(journal.claimNext("other-runner"))
            reopen()
            val uncertain = journal.jobs().single()
            assertEquals(TransferState.Uncertain, uncertain.state)
            assertEquals(receipt.path, uncertain.destination)
            assertNull(journal.accept(claim, receipt))
            val resumed = journal.claimNext("process-2")!!
            assertEquals(
                receipt.path,
                journal.pin(resumed, listOf("wrong", "new", "path"))!!.destination,
            )
            assertNotNull(journal.accept(resumed, receipt.copy(created = false)))
        }

    @Test fun unfinishedStepsRunAfterReopenAndAFinishedOneDoesNot() =
        runBlocking {
            enqueue()
            val accepted = journal.accept(journal.claimNext("process-1")!!, receipt)!!
            journal.step(accepted, FollowUp.Link, StepState.Done)
            reopen()
            val restored = journal.jobs().single()
            assertEquals(
                mapOf(FollowUp.Link to StepState.Done, FollowUp.Album to StepState.Pending),
                restored.steps,
            )
            assertNull(restored.executor)
            assertTrue(restored.runnable)
            assertTrue(journal.hasUnfinished())
            assertNotNull(journal.claimNext("process-2"))
        }

    @Test fun deferredJobIsSkippedForTheRestOfThePassAndFencesItsOldClaim() =
        runBlocking {
            enqueue()
            val claim = journal.claimNext("process-1")!!
            val deferred = journal.defer(claim)!!
            assertEquals(TransferState.Uncertain, deferred.state)
            assertNull(deferred.executor)
            assertNull(journal.claimNext("process-1", skip = setOf(claim.id)))
            assertNull(journal.accept(claim, receipt))
            assertNotNull(journal.claimNext("process-1"))
        }

    @Test fun selectionSurvivesReopen() =
        runBlocking {
            enqueue()
            reopen()
            val restored = journal.jobs().single()
            assertEquals(file, restored.selection)
            assertEquals("content://media/external/images/media/42", restored.selection.identityUri)
            assertEquals(UploadAlbum("test", "task-album"), restored.album)
        }

    @Test fun rejectedStorageCommitDoesNotAcknowledgeEnqueue() =
        runBlocking {
            // Room opens lazily; a database never opened would open again on the next write.
            journal.jobs()
            db.close()
            assertEquals(EnqueueResult.Rejected, journal.enqueue(listOf(file), null))
        }
}

package com.syrok0010.nextgallery.feature.upload

import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.media.*
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.*
import org.junit.Assert.*

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
    private var albums = 0
    private val albumModes = mutableListOf<AlbumStepMode>()
    private var transfers = 0
    private var allowLink = true
    private var albumSuccess = true
    private val ids = listOf(MediaId("local-a"), MediaId("local-b"))

    @Before fun setup() {
        context.deleteDatabase(dbName)
        library.publish(ids.map { id -> media(id) })
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
        queue = UploadQueue(sessions, library, scope, journal,
            { stamp ?: throw SecurityException() },
            { _, _, _ -> links++; allowLink },
            { _, _, _, mode, current -> albumModes += mode; albums++; if (current() && albumSuccess) UploadStepResult("Done") else UploadStepResult.attention("Album") }, {}, UUID.randomUUID().toString())
    }
    @After fun cleanup() = runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close(); context.deleteDatabase(dbName); Unit }
    private suspend fun reopen() {
        scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close(); open()
    }
    private suspend fun enqueue(ids: List<MediaId> = this.ids, intents: List<UploadIntent> = emptyList()): UploadBatch {
        assertTrue(queue.submit(ids, intents) is EnqueueResult.Accepted)
        return queue.waiting.value.last()
    }
    private suspend fun transfer(account: AccountCredentials, item: UploadItem, claim: UploadJob): UploadedFile {
        transfers++
        val url = claim.destination ?: queue.pinDestination(claim, "http://test.syrok/remote.php/dav/files/test/Photos/${item.selection.displayName}")!!
        return UploadedFile(item.selection, url.toHttpUrl(), true, 42)
    }
    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }

    @Test fun queueReopenRetainsStableRequestAndAllThreeCancellationScopes() = runBlocking {
        val result = queue.submit(ids, requestId = "stable-request") as EnqueueResult.Accepted
        queue.cancel(queue.batches.value.single().items.first())
        waitFor { queue.batches.value.single().items.first().state.value.phase == UploadPhase.Cancelled }
        reopen()
        val duplicate = queue.submit(ids, requestId = "stable-request")
        assertEquals(result, duplicate)
        assertEquals(2, queue.batches.value.single().items.size)
        queue.cancel(queue.batches.value.single())
        waitFor { queue.batches.value.single().items.all { it.state.value.phase == UploadPhase.Cancelled } }
        enqueue(listOf(ids.first()))
        queue.cancelAll()
        waitFor { queue.batches.value.flatMap { it.items }.all { it.state.value.phase == UploadPhase.Cancelled } }
        var loggedOut = false
        assertTrue(queue.logout { loggedOut = true; sessions.signOut() })
        assertTrue(loggedOut)
    }

    @Test fun cancelCommittedBeforeTransferResponsePreventsLinkAndAlbumAfterReopen() = runBlocking {
        val batch = enqueue(intents = listOf(UploadIntent("album", owner = "test", target = "slice1")))
        val response = CompletableDeferred<Unit>()
        val sending = CompletableDeferred<Unit>()
        val runner = async(Dispatchers.Default) {
            queue.runBatch(batch) { account, item, claim ->
                sending.complete(Unit); response.await(); transfer(account, item, claim)
            }
        }
        sending.await()
        queue.cancelAll()
        waitFor { batch.items.all { it.state.value.phase == UploadPhase.Cancelled } }
        response.complete(Unit); runner.await()
        assertEquals(0, links); assertEquals(0, albums)
        reopen()
        waitFor { queue.batches.value.isNotEmpty() }
        assertTrue(queue.waiting.value.isEmpty())
        assertTrue(queue.batches.value.single().items.all { it.state.value.phase == UploadPhase.Cancelled })
    }

    @Test fun competingExecutorsCannotTransferSameClaimTwice() = runBlocking {
        val batch = enqueue(listOf(ids.first()))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) { queue.runBatch(batch) { account, item, claim -> entered.complete(Unit); finish.await(); transfer(account, item, claim) } }
        entered.await()
        queue.runBatch(batch, ::transfer)
        finish.complete(Unit); first.await()
        assertEquals(1, transfers); assertEquals(1, links)
    }

    @Test fun durableReceiptRunsOnlyStepsEvenWhenSourceDisappeared() = runBlocking {
        val batch = enqueue(listOf(ids.first()))
        val claim = journal.claim(batch.items.single().id, credentials.uploadOwner, "dead-process")!!
        val receipt = UploadReceipt("http://test.syrok/remote.php/dav/files/test/Photos/a.jpg", true, 42)
        journal.accept(claim, receipt)
        stamp = null
        reopen()
        val restored = withTimeout(5_000) { queue.waiting.first { it.isNotEmpty() } }.single()
        queue.runBatch(restored) { _, _, _ -> error("Receipt must not trigger PUT or source access") }
        assertEquals(0, transfers); assertEquals(1, links)
        assertEquals(UploadPhase.Uploaded, restored.items.single().state.value.phase)
    }

    @Test fun interruptedConditionalDestinationIsUsedAfterReopen() = runBlocking {
        val batch = enqueue(listOf(ids.first()))
        val claim = journal.claim(batch.items.single().id, credentials.uploadOwner, "dead-process")!!
        val fixed = "http://test.syrok/remote.php/dav/files/test/Photos/original.jpg"
        journal.destination(claim, fixed)
        reopen()
        val restored = withTimeout(5_000) { queue.waiting.first { it.isNotEmpty() } }.single()
        queue.runBatch(restored) { _, item, job ->
            assertEquals(fixed, job.destination)
            transfers++
            UploadedFile(item.selection, job.destination!!.toHttpUrl(), false, 42)
        }
        assertEquals(1, transfers); assertEquals(1, links)
        assertEquals(UploadPhase.Existing, restored.items.single().state.value.phase)
    }

    @Test fun changedOrInaccessibleOriginalRequiresAttentionAndBlocksLogoutUntilCancel() = runBlocking {
        val batch = enqueue()
        stamp = stamp!!.copy(generation = 21)
        queue.runBatch(batch, ::transfer)
        assertEquals(0, transfers)
        assertTrue(batch.items.all { it.state.value.phase == UploadPhase.NeedsAttention })
        assertFalse(queue.logout { error("Unfinished jobs must block logout") })
        queue.clearFinished()
        delay(50)
        assertEquals(1, queue.batches.value.size)
        queue.cancelAll()
        waitFor { batch.items.all { it.state.value.phase == UploadPhase.Cancelled } }
        assertTrue(queue.logout { sessions.signOut() })
    }

    @Test fun acceptedUploadWithUncertainAlbumRemainsVisibleAndCanBeExplicitlyAbandoned() = runBlocking {
        val batch = enqueue(listOf(ids.first()), listOf(UploadIntent("album", owner = "test", target = "slice1")))
        val claim = journal.claim(batch.items.single().id, credentials.uploadOwner, "dead-process")!!
        val accepted = journal.accept(claim, UploadReceipt("http://test.syrok/a", true, 42))!!
        journal.step(accepted, "album", StepState.Running)
        reopen()
        val restored = withTimeout(5_000) { queue.waiting.first { it.isNotEmpty() } }.single()
        queue.runBatch(restored) { _, _, _ -> error("No second PUT") }
        assertEquals(0, albums)
        val state = restored.items.single().state.value
        assertEquals(UploadPhase.Uploaded, state.phase); assertTrue(state.attention); assertTrue(state.pendingAction)
        assertFalse(queue.logout { error("Pending album action") })
        queue.cancel(restored.items.single())
        waitFor { !restored.items.single().state.value.pendingAction }
        assertEquals(UploadPhase.Uploaded, restored.items.single().state.value.phase)
        assertTrue(queue.logout { sessions.signOut() })
    }

    @Test fun successfulHistoryDoesNotBlockLogoutAndChangedAccountAddressCannotReuseJobs() = runBlocking {
        val batch = enqueue(listOf(ids.first()))
        queue.runBatch(batch, ::transfer)
        assertEquals(UploadPhase.Uploaded, batch.items.single().state.value.phase)
        assertTrue(queue.batches.value.isNotEmpty())
        assertTrue(queue.logout { sessions.signOut() })
        sessions.signIn(credentials.copy(serverUrl = "http://other.example/"))
        delay(100)
        assertTrue(queue.batches.value.isEmpty())
        assertEquals(0, albums)
    }

    @Test fun historyIsExcludedFromNewPassButConcurrentEnqueueExtendsCurrentPass() = runBlocking {
        val completed = enqueue(listOf(ids.first()))
        queue.runBatch(completed, ::transfer)
        assertNull(queue.activity.value)
        val pending = enqueue(listOf(ids.last()))
        assertEquals(1, queue.activity.value!!.total)
        assertEquals(0, queue.activity.value!!.processed)
        enqueue(listOf(ids.first()))
        assertEquals(2, queue.activity.value!!.total)
        assertEquals(0, queue.activity.value!!.processed)
        assertEquals(3, queue.batches.value.size)
        queue.cancelAll()
    }

    @Test fun completedAndCancelledFilesDoNotKeepProgressWhileAnotherFileWaits() = runBlocking {
        val active = enqueue()
        val waiting = enqueue(listOf(ids.first()))
        queue.runBatch(active) { account, item, claim ->
            if (item == active.items.first()) {
                queue.uploading(item.selection.contentUri, 100)
            } else {
                assertEquals(1, queue.activity.value!!.processed)
                assertNull("Completed file must not remain current", queue.activity.value!!.current)
                assertNull(queue.activity.value!!.percent)
                queue.uploading(item.selection.contentUri, 25)
                queue.consumeResult()
                waitFor { queue.result.value == null }
                assertEquals(item.selection.identityUri, queue.activity.value!!.current)
                assertEquals(25, queue.activity.value!!.percent)
                queue.cancel(item)
                waitFor { item.state.value.phase == UploadPhase.Cancelled }
                assertNull("Cancelled file must not remain current", queue.activity.value!!.current)
                assertNull(queue.activity.value!!.percent)
            }
            transfer(account, item, claim)
        }
        assertEquals(UploadPhase.Uploaded, active.items.first().state.value.phase)
        assertEquals(UploadPhase.Cancelled, active.items.last().state.value.phase)
        assertEquals(UploadPhase.Waiting, waiting.items.single().state.value.phase)
        assertEquals(2, queue.activity.value!!.processed)
        assertEquals(3, queue.activity.value!!.total)
        assertEquals(1, links)
    }

    @Test fun newlyAcceptedAlbumRunsOnceAndReceiptPendingAlbumDoesNotReplayOnReopen() = runBlocking {
        val batch = enqueue(listOf(ids.first()), listOf(UploadIntent("album", version = 2, owner = "test", target = "slice1", albumId = 1, albumHref = "http://test.syrok/album")))
        queue.runBatch(batch, ::transfer)
        assertEquals(1, albums)
        assertEquals("slice1", batch.items.single().state.value.albumAdded)
        val next = enqueue(listOf(ids.last()), listOf(UploadIntent("album", owner = "test", target = "slice2")))
        val claim = journal.claim(next.items.single().id, credentials.uploadOwner, "dead-process")!!
        journal.accept(claim, UploadReceipt("http://test.syrok/a", true, 42))
        reopen()
        val restored = withTimeout(5_000) { queue.waiting.first { it.isNotEmpty() } }.single()
        queue.runBatch(restored) { _, _, _ -> error("No transfer for saved receipt") }
        assertEquals(1, albums)
        assertTrue(restored.items.single().state.value.attention)
        assertFalse(queue.logout { error("Pending album action must block logout") })
    }

    @Test fun albumCopyPermissionIsOnlyForThePassThatAcceptsReceipt() = runBlocking {
        val intent = UploadIntent("album", 2, "test", "slice", 1, "http://test.syrok/album")
        albumSuccess = false
        val fresh = enqueue(listOf(ids.first()), listOf(intent))
        queue.runBatch(fresh, ::transfer)
        assertEquals(listOf(AlbumStepMode.Initial), albumModes)
        queue.retryActions(fresh.items.single())
        withTimeout(5_000) { queue.waiting.first { it.isNotEmpty() } }
        queue.runBatch(fresh) { _, _, _ -> error("Retry must not transfer") }
        assertEquals(listOf(AlbumStepMode.Initial, AlbumStepMode.Reconcile), albumModes)
        val pending = enqueue(listOf(ids.last()), listOf(intent))
        val claim = journal.claim(pending.items.single().id, credentials.uploadOwner, "dead-process")!!
        journal.accept(claim, UploadReceipt("http://test.syrok/remote.php/dav/files/test/Photos/b.jpg", true, 43))
        reopen()
        val restored = withTimeout(5_000) { queue.waiting.first { it.isNotEmpty() } }.single()
        queue.runBatch(restored) { _, _, _ -> error("Existing receipt must not transfer") }
        assertEquals(listOf(AlbumStepMode.Initial, AlbumStepMode.Reconcile, AlbumStepMode.Reconcile), albumModes)
        assertEquals(1, transfers)
    }

    @Test fun missingCredentialsDoNotEraseJournalAndEmptySelectionIsNotAStorageFailure() = runBlocking {
        assertEquals(EnqueueResult.NothingToUpload, queue.submit(emptyList()))
        assertFalse(queue.storageError.value)
        val batch = enqueue(listOf(ids.first()))
        journal.activate(null)
        assertEquals(1, journal.jobs(credentials.uploadOwner).size)
        assertFalse(journal.revokeIfFinished(credentials.uploadOwner))
        assertEquals(UploadPhase.Waiting, batch.items.single().state.value.phase)
    }

    @Test fun unknownIntentVersionIsAttentionAndNeverExecutesTransfer() = runBlocking {
        val batch = enqueue(listOf(ids.first()), listOf(UploadIntent("album", version = 99)))
        queue.runBatch(batch, ::transfer)
        assertEquals(0, transfers); assertEquals(UploadPhase.NeedsAttention, batch.items.single().state.value.phase)
    }

    @Test fun rejectedJournalDoesNotConsumeSelectionOrPublishAcceptedBatch() = runBlocking {
        queue.submit(emptyList()) // await startup
        db.close()
        assertEquals(EnqueueResult.Rejected, queue.submit(ids))
        assertEquals(2, library.state.value.size)
        assertTrue(queue.batches.value.isEmpty())
    }

    @Test fun executorCancellationStopsTheWholeReleasedPassWithoutAReopenLoop() = runBlocking {
        val active = enqueue()
        val queued = enqueue(listOf(ids.first()))
        assertEquals(listOf(active, queued), queue.release())
        assertEquals(active, queue.next())
        val sending = CompletableDeferred<Unit>()
        val runner = launch(Dispatchers.Default) {
            queue.runBatch(active) { _, _, _ -> sending.complete(Unit); awaitCancellation() }
        }
        sending.await()
        runner.cancelAndJoin()
        assertTrue("Every released file must expose the executor stop", (active.items + queued.items).all {
            it.state.value.phase == UploadPhase.NeedsAttention
        })
        assertNull(queue.next())
        assertEquals(0, queue.releasedFiles())
        assertTrue(queue.waiting.value.isEmpty())
        assertNull(queue.activity.value)
        reopen()
        waitFor { queue.batches.value.size == 2 }
        assertTrue(queue.waiting.value.isEmpty())
        assertTrue(queue.batches.value.flatMap { it.items }.all { it.state.value.phase == UploadPhase.NeedsAttention })
        assertFalse(queue.logout { error("Stopped jobs need explicit cancellation") })
        val fresh = enqueue(listOf(ids.last()))
        assertEquals(listOf(fresh), queue.release())
    }

    @Test fun viewingAttentionConsumesOnlyTheSummaryAndCancellationPublishesANewResult() = runBlocking {
        val batch = enqueue(listOf(ids.first()))
        stamp = null
        queue.runBatch(batch, ::transfer)
        assertEquals(1, queue.result.value!!.attention)
        queue.consumeResult()
        waitFor { queue.result.value == null }
        assertEquals(UploadPhase.NeedsAttention, batch.items.single().state.value.phase)
        assertFalse(queue.logout { error("Viewing a result does not finish the job") })
        reopen()
        waitFor { queue.batches.value.isNotEmpty() }
        assertNull(queue.result.value)
        queue.cancelAll()
        waitFor { queue.result.value?.cancelled == 1 }
        assertTrue(queue.logout { sessions.signOut() })
    }

    @Test fun stopBetweenBatchesPreservesReceiptAndSuppressesReleasedPendingAlbumWithoutPut() = runBlocking {
        val completed = enqueue(listOf(ids.first()))
        val pending = enqueue(listOf(ids.last()), listOf(UploadIntent("album", owner = "test", target = "slice1")))
        queue.release()
        val pendingClaim = journal.claim(pending.items.single().id, credentials.uploadOwner, "fixture")!!
        val receipt = UploadReceipt("http://test.syrok/already-uploaded", true, 43)
        val accepted = journal.accept(pendingClaim, receipt)!!
        journal.release(accepted)
        assertEquals(completed, queue.next())
        queue.runBatch(completed, ::transfer)
        assertEquals(1, transfers)
        queue.stopExecutor()
        val stopped = journal.jobs(credentials.uploadOwner).last()
        assertEquals(receipt, stopped.receipt)
        assertEquals("Attention", stopped.linkStep)
        assertEquals("Attention", stopped.albumStep)
        assertNull(stopped.executor)
        assertNull(queue.next())
        assertTrue(queue.waiting.value.isEmpty())
        queue.consumeResult()
        waitFor { queue.result.value == null }
        assertTrue(pending.items.single().state.value.pendingAction)
        assertFalse(queue.logout { error("Unresolved steps must block logout") })
        queue.cancel(pending)
        waitFor { !pending.items.single().state.value.pendingAction }
        assertEquals(UploadPhase.Uploaded, pending.items.single().state.value.phase)
        assertEquals(1, queue.result.value!!.uploaded)
        assertEquals(1, transfers)
        assertTrue(queue.logout { sessions.signOut() })
    }

    @Test fun versionedReceiptRecoveryRunsOnlyStepsEvenWhenOriginalUriDisappeared() = runBlocking {
        val batch = enqueue(listOf(ids.first()), listOf(UploadIntent("album", version = 2, owner = "test", target = "slice2", albumId = 12, albumHref = "http://test.syrok/remote.php/dav/photos/test/albums/slice2")))
        val claim = journal.claim(batch.items.single().id, credentials.uploadOwner, "dead-process")!!
        val receipt = UploadReceipt("http://test.syrok/remote.php/dav/files/test/Photos/local-a.jpg", true, 42)
        journal.accept(claim, receipt)
        stamp = null
        reopen()
        val restored = withTimeout(5_000) { queue.waiting.first { it.isNotEmpty() } }.single()
        queue.runBatch(restored) { _, _, _ -> error("Receipt recovery must never PUT or read source") }
        assertEquals(1, links)
        assertEquals(1, albums)
        assertEquals(0, transfers)
        assertFalse(restored.items.single().state.value.pendingAction)
        assertEquals(receipt, journal.jobs(credentials.uploadOwner).single().receipt)
    }

    @Test fun manualRetryFinishesOnlyFailedStepWithoutOriginalOrNewPut() = runBlocking {
        allowLink = false
        val batch = enqueue(listOf(ids.first()))
        queue.runBatch(batch, ::transfer)
        assertEquals(1, transfers)
        assertTrue(batch.items.single().state.value.retryActions)
        val receipt = journal.jobs(credentials.uploadOwner).single().receipt
        queue.consumeResult()
        waitFor { queue.result.value == null }
        stamp = null
        allowLink = true
        queue.retryActions(batch.items.single())
        waitFor { queue.waiting.value.isNotEmpty() }
        queue.runBatch(batch) { _, _, _ -> error("Retry must not PUT") }
        assertEquals(2, links)
        assertEquals(1, transfers)
        assertEquals(receipt, journal.jobs(credentials.uploadOwner).single().receipt)
        assertFalse(batch.items.single().state.value.pendingAction)
        assertEquals(1, queue.result.value!!.uploaded)
        reopen()
        waitFor { queue.batches.value.isNotEmpty() }
        assertFalse(queue.batches.value.single().items.single().state.value.pendingAction)
    }

    @Test fun albumRetryNeverRepeatsCompletedLinkAndReceiptSurvivesReopen() = runBlocking {
        albumSuccess = false
        val batch = enqueue(listOf(ids.first()), listOf(UploadIntent("album", version = 2, owner = "test", target = "retry", albumId = 5, albumHref = "http://test.syrok/album")))
        queue.runBatch(batch, ::transfer)
        assertEquals(1, links); assertEquals(1, albums)
        val original = journal.jobs(credentials.uploadOwner).single()
        assertEquals("Done", original.linkStep)
        queue.consumeResult()
        waitFor { queue.result.value == null }
        reopen()
        waitFor { queue.batches.value.isNotEmpty() }
        val restored = queue.batches.value.single()
        assertNull(queue.result.value)
        albumSuccess = true
        queue.retryActions(restored.items.single())
        waitFor { queue.waiting.value.isNotEmpty() }
        queue.runBatch(restored) { _, _, _ -> error("No PUT") }
        assertEquals(1, links); assertEquals(2, albums); assertEquals(1, transfers)
        assertEquals(original.receipt, journal.jobs(credentials.uploadOwner).single().receipt)
        assertEquals(original.linkResultJson, journal.jobs(credentials.uploadOwner).single().linkResultJson)
        assertFalse(restored.items.single().state.value.pendingAction)
        assertNotNull(queue.result.value)
    }

    @Test fun cancelDuringStepFencesLateSuccessAndSuppressesNextStep() = runBlocking {
        val queued = enqueue(listOf(ids.first()), listOf(UploadIntent("album", version = 2, owner = "test", target = "cancel", albumId = 5, albumHref = "http://test.syrok/album")))
        val accepted = journal.accept(journal.claim(queued.items.single().id, credentials.uploadOwner, "fixture")!!,
            UploadReceipt("http://test.syrok/source.jpg", true, 42))!!
        val running = journal.beginStep(accepted, "link")!!
        journal.cancel(credentials.uploadOwner)
        assertFalse(journal.stepCurrent(running, "link"))
        assertNull(journal.finishStep(running, "link", UploadStepResult("Done", fileId = 42)))
        assertNull(journal.beginStep(running, "album"))
        reopen()
        waitFor { queue.batches.value.isNotEmpty() }
        assertEquals(0, albums); assertEquals(0, links); assertEquals(0, transfers)
        assertEquals("Suppressed", journal.jobs(credentials.uploadOwner).single().linkStep)
        assertEquals("Suppressed", journal.jobs(credentials.uploadOwner).single().albumStep)
        assertEquals(UploadPhase.Uploaded, queue.batches.value.single().items.single().state.value.phase)
    }

    @Test fun twoStepClaimsAndOldProcessReplyCannotCommitAcrossRecovery() = runBlocking {
        val batch = enqueue(listOf(ids.first()))
        val accepted = journal.accept(journal.claim(batch.items.single().id, credentials.uploadOwner, "old")!!,
            UploadReceipt("http://test.syrok/source.jpg", true, 42))!!
        val old = journal.beginStep(accepted, "link")!!
        assertNull(journal.beginStep(accepted, "link"))
        reopen()
        val restored = withTimeout(5_000) { queue.waiting.first { it.isNotEmpty() } }.single()
        assertNull(journal.finishStep(old, "link", UploadStepResult("Done")))
        queue.runBatch(restored) { _, _, _ -> error("No PUT") }
        assertEquals(1, links)
        assertEquals("Done", journal.jobs(credentials.uploadOwner).single().linkStep)
    }

    @Test fun unknownPersistedIntentLeavesReceiptVisibleAndReportsFormatWithoutPut() = runBlocking {
        val batch = enqueue(listOf(ids.first()), listOf(UploadIntent("album", version = 99)))
        val claim = journal.claim(batch.items.single().id, credentials.uploadOwner, "old-process")!!
        val receipt = UploadReceipt("http://test.syrok/remote.php/dav/files/test/Photos/local-a.jpg", true, 42)
        journal.accept(claim, receipt)
        reopen()
        val restored = withTimeout(5_000) { queue.waiting.first { it.isNotEmpty() } }.single()
        queue.runBatch(restored) { _, _, _ -> error("Unknown intent cannot PUT") }
        val state = restored.items.single().state.value
        assertEquals(UploadPhase.Uploaded, state.phase)
        assertEquals("Format", state.linkProblem)
        assertTrue(state.attention)
        assertEquals(receipt, journal.jobs(credentials.uploadOwner).single().receipt)
        assertEquals(0, links); assertEquals(0, albums); assertEquals(0, transfers)
    }

    private fun media(id: MediaId) = MediaItem(id, 1, "${id.value}.jpg", "image/jpeg", null, null, null, null, null, null, null,
        1, false, null, false, false, MediaAssetRef.LocalContent("content://media/external/images/media/${if (id == ids.first()) 1 else 2}", 1))
}

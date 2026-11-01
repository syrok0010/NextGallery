package com.syrok0010.nextgallery.app.ui

import androidx.compose.runtime.mutableStateOf
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.albums.*
import com.syrok0010.nextgallery.feature.deletion.*
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesFiles
import com.syrok0010.nextgallery.feature.upload.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationStatusTest {
    @Test fun `waiting and released uploads block logout through the last follow-up`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.uploads.submit(UploadBatch(listOf(file)))
        assertNull(fixture.uploads.activity.value?.current)
        assertEquals(1, fixture.uploads.waiting.value.size)
        fixture.sync()
        fixture.assertBlocked()

        val batch = fixture.uploads.release().single()
        fixture.uploads.next()
        fixture.uploads.uploading(file.contentUri, 50)
        fixture.sync()
        fixture.assertBlocked()
        fixture.uploads.batchFinished(batch)
        fixture.sync()
        fixture.assertBlocked() // All bytes processed; follow-ups may still be running.

        fixture.uploads.passFinished(UploadResult(1, 1))
        fixture.sync()
        assertTrue(fixture.status.active) // Unseen success is not a running operation.
        fixture.assertAllowed()
    }

    @Test fun `live sources reject logout before the menu snapshot sees a new operation`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        assertFalse(fixture.status.running)
        fixture.uploads.submit(UploadBatch(listOf(file)))
        assertFalse(fixture.status.running) // Simulates collection/recomposition not having run.
        var signedOut = false
        assertFalse(fixture.status.tryLogout { signedOut = true })
        assertFalse(signedOut)
    }

    @Test fun `album addition and queued cloud deletion independently block logout`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.additions.add(AlbumTarget.New("test"), AlbumAdditionRequest(listOf(1)))
        // Neither job has run yet; their public state already protects the account.
        assertFalse(fixture.status.tryLogout { fail("Album operation was accepted") })
        fixture.sync()
        fixture.assertBlocked()

        val deletionOnly = Fixture(backgroundScope)
        deletionOnly.deletions.delete(DeletionTargets(emptyList(), listOf(1)))
        assertFalse(deletionOnly.status.tryLogout { fail("Cloud deletion was queued") })
        deletionOnly.sync()
        deletionOnly.assertBlocked()
    }

    @Test fun `album removal synchronously blocks logout and marks only its members`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        assertTrue(fixture.removals.remove(AlbumLocation.Remote("test/album"), "album", setOf(1)))
        assertFalse(fixture.status.tryLogout { fail("Removal already accepted") })
        fixture.sync()
        fixture.assertBlocked()
    }

    @Test fun `phone-only verification does not block logout`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.assertAllowed()
        fixture.deletions.delete(DeletionTargets(listOf("content://media/external/images/media/1"), emptyList()))
        fixture.sync()
        fixture.assertAllowed() // Phone-only verification has no cloud work.
    }

    @Test fun `unseen errors and successful results do not block logout`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.uploads.passFinished(UploadResult(0, 1, failures = listOf("network")))
        fixture.uploadResult.value = fixture.uploads.result.value
        fixture.addition.value = AlbumAdditionState.Failed("test", AlbumAdditionFailure.Network)
        fixture.deletionResult.value = DeletionResult(0, CloudDeleteResult(0, 1))
        assertTrue(fixture.status.active)
        fixture.assertAllowed()
        fixture.addition.value = AlbumAdditionState.Done("test", AlbumAddResult(1, 0, 0), 0)
        fixture.assertAllowed()
    }

    @Test fun `repeated cloud deletion blocks logout until the second batch succeeds`() =
        checkRepeatedDeletion(secondResponse = 204)

    @Test fun `repeated cloud deletion blocks logout until the second batch fails`() =
        checkRepeatedDeletion(secondResponse = 500)

    private fun checkRepeatedDeletion(secondResponse: Int) = runBlocking {
        val entered = List(2) { CompletableDeferred<Unit>() }
        val release = List(2) { CountDownLatch(1) }
        val deletes = AtomicInteger()
        MemoriesAlbumWriterTest.Fixture { request ->
            if (request.method == "DELETE") {
                val index = deletes.getAndIncrement()
                entered[index].complete(Unit)
                check(release[index].await(5, TimeUnit.SECONDS)) { "DELETE was not released" }
                (if (index == 0) 204 else secondResponse) to ""
            } else {
                200 to """{"filename":"/Photos/a.jpg","basename":"a.jpg"}"""
            }
        }.use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val fixture = Fixture(scope, server.credentials, server.writerTransport)
                val targets = DeletionTargets(emptyList(), listOf(1))
                fixture.deletions.delete(targets)
                withTimeout(5_000) { entered[0].await() }
                fixture.deletions.delete(targets)
                fixture.sync()
                fixture.assertBlocked()
                release[0].countDown()
                withTimeout(5_000) { entered[1].await() }

                // The first batch is done, while the second DELETE is still on the wire.
                var signedOut = false
                assertFalse(fixture.status.tryLogout { signedOut = true })
                assertFalse(signedOut)
                fixture.sync()
                fixture.assertBlocked()
                assertEquals(setOf(1L), fixture.deletions.progress.value?.fileIds)
                assertEquals(2, fixture.deletions.progress.value?.total)
                assertEquals(1, fixture.deletions.progress.value?.processed)

                release[1].countDown()
                withTimeout(5_000) { fixture.deletions.progress.first { it == null } }
                fixture.sync()
                fixture.assertAllowed()
                assertEquals(
                    CloudDeleteResult(if (secondResponse == 204) 2 else 1, if (secondResponse == 204) 0 else 1),
                    fixture.deletions.result.value?.cloud,
                )
            } finally {
                release.forEach { it.countDown() }
                scope.cancel()
            }
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        private val credentials: AccountCredentials = AccountCredentials("https://example.com", "test", "secret", "test"),
        transport: NextcloudTransport = NextcloudTransport(Json),
    ) {
        private val sessions = SessionStore(object : CredentialsStore {
            override fun load() = credentials
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        })
        private val files = MemoriesFiles(transport)
        val uploads = UploadQueue(sessions, scope)
        private val catalog = AlbumCatalogRepository(
            sessions,
            object : RemoteAlbumSource {
                override suspend fun load(credentials: AccountCredentials) = RemoteAlbums(emptyList())
            },
            object : LocalAlbumSource {
                override suspend fun load() = emptyList<AlbumSummary>()
                override fun changes() = emptyFlow<Unit>()
            },
            MutableStateFlow<LocalMediaPermissionMode?>(null),
            scope,
        )
        val additions = AlbumAdditions(sessions, MemoriesAlbumWriter(transport, files), catalog, uploads, scope)
        val deletions = MediaDeletionRepository(
            sessions, MediaLibraryIndex(), MemoriesFileDeleter(transport, files), { it.size }, scope,
        ) {}
        private val upload = mutableStateOf<UploadActivity?>(null)
        val uploadResult = mutableStateOf<UploadResult?>(null)
        val addition = mutableStateOf<AlbumAdditionState>(AlbumAdditionState.Idle)
        private val deletion = mutableStateOf<DeletionProgress?>(null)
        val deletionResult = mutableStateOf<DeletionResult?>(null)
        val removals = AlbumRemovals(sessions, MemoriesAlbumRemover(transport), scope) {}
        private val removal = mutableStateOf<AlbumRemovalProgress?>(null)
        private val removalResult = mutableStateOf<AlbumRemovalOutcome?>(null)
        val status = OperationStatus(uploads, removals, removal, removalResult, additions, deletions, upload, uploadResult, addition, deletion, deletionResult)

        fun sync() {
            removal.value = removals.progress.value
            removalResult.value = removals.result.value
            upload.value = uploads.activity.value
            uploadResult.value = uploads.result.value
            addition.value = additions.state.value
            deletion.value = deletions.progress.value
            deletionResult.value = deletions.result.value
        }

        fun assertBlocked() {
            assertTrue(status.running)
            assertFalse(status.tryLogout { fail("Logout must be blocked") })
        }

        fun assertAllowed() {
            assertFalse(status.running)
            var signedOut = false
            assertTrue(status.tryLogout { signedOut = true })
            assertTrue(signedOut)
        }
    }

    private companion object {
        val file = UploadSelection("content://media/1", "test.jpg", "image/jpeg")
    }
}

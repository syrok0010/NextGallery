package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class UploadQueueTest {
    private val credentials = AccountCredentials("http://cloud", "anna", "secret", "anna")
    private val library = MediaLibraryIndex().apply {
        publish(
            listOf(
                item("a", local("a")),
                item("b", local("b")),
                item("c", local("c")),
                item("cloud", MediaAssetRef.MemoriesFile(10)),
                item(
                    "both",
                    MediaAssetRef.LocalFirst(local("both"), MediaAssetRef.MemoriesFile(11)),
                ),
            ),
        )
    }

    @Test fun `submit fixes phone copies of local-only objects and skips the rest`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val queue = UploadQueue(SessionStore(Fixed(credentials)), library, scope)
                assertEquals(1, queue.submit(ids("a", "cloud", "both", "gone", "a")))
                assertEquals(0, queue.submit(ids("cloud")))
                library.clear()

                assertEquals(listOf(files("a")), queue.waiting.value.map { it.files })
            } finally {
                scope.cancel()
            }
        }

    @Test fun `released batches go to the service in order while newer ones keep waiting`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val queue = UploadQueue(SessionStore(Fixed(credentials)), library, scope)
                queue.submit(ids("a"))
                queue.submit(ids("b"))
                assertEquals(listOf(files("a"), files("b")), queue.release().map { it.files })
                queue.submit(ids("c"))

                assertEquals(2, queue.releasedFiles())
                assertEquals(files("a"), queue.next()?.files)
                assertEquals(
                    "waiting batches join the total only once released",
                    1,
                    queue.releasedFiles(),
                )
                assertEquals(files("b"), queue.next()?.files)
                assertNull(queue.next())
                assertEquals(listOf(files("c")), queue.waiting.value.map { it.files })
            } finally {
                scope.cancel()
            }
        }

    @Test fun `every batch gets the common follow-ups before its own`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val common = mutableListOf<UploadFollowUp>()
                val queue = UploadQueue(SessionStore(Fixed(credentials)), library, scope) {
                    listOf(Recording()).also(common::addAll)
                }
                val own = Recording()
                queue.submit(ids("a"), listOf(own))
                queue.submit(ids("b"))
                queue.release()

                assertEquals(listOf(common[0], own), queue.next()?.followUps)
                assertEquals("one instance per batch", listOf(common[1]), queue.next()?.followUps)
            } finally {
                scope.cancel()
            }
        }

    @Test fun `refused start returns untaken batches ahead of newer ones`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val queue = UploadQueue(SessionStore(Fixed(credentials)), library, scope)
                queue.submit(ids("a"))
                queue.submit(ids("b"))
                val released = queue.release()
                // A service that was already running took one batch before the new start was refused.
                assertEquals(files("a"), queue.next()?.files)
                queue.submit(ids("c"))

                queue.restore(released)

                assertEquals(
                    listOf(files("b"), files("c")),
                    queue.waiting.value.map { it.files },
                )
                assertNull(queue.next())
                assertEquals(0, queue.releasedFiles())
            } finally {
                scope.cancel()
            }
        }

    @Test fun `logout drops waiting and released batches`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val sessions = SessionStore(Fixed(credentials))
                val queue = UploadQueue(sessions, library, scope)
                queue.submit(ids("a"))
                queue.release()
                queue.submit(ids("b"))
                yield()
                sessions.signOut()
                withTimeout(5_000) { queue.waiting.first { it.isEmpty() } }
                assertNull(queue.next())
            } finally {
                scope.cancel()
            }
        }

    @Test fun `activity follows files until the queue is empty and keeps the result`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val sessions = SessionStore(Fixed(credentials))
                val queue = UploadQueue(sessions, library, scope)
                queue.submit(ids("a", "b"))
                val first = queue.waiting.value.single()
                queue.uploading("content://media/a", 40)
                queue.submit(ids("c"))
                assertEquals(
                    UploadActivity(
                        pending = setOf(
                            "content://media/a",
                            "content://media/b",
                            "content://media/c",
                        ),
                        total = 3,
                        processed = 0,
                        current = "content://media/a",
                        percent = 40,
                    ),
                    queue.activity.value,
                )

                queue.processed("content://media/a")
                // Stopped on an error: the rest of the batch is done too.
                queue.batchFinished(first)
                assertEquals(
                    UploadActivity(pending = setOf("content://media/c"), total = 3, processed = 2),
                    queue.activity.value,
                )
                queue.processed("content://media/c")
                assertEquals(
                    "follow-ups of the last batch still run",
                    UploadActivity(pending = emptySet(), total = 3, processed = 3),
                    queue.activity.value,
                )

                queue.passFinished(
                    UploadResult(uploaded = 1, files = 2, failures = listOf("HTTP 500")),
                )
                assertNull(queue.activity.value)
                queue.passFinished(UploadResult(uploaded = 1, files = 1))
                assertEquals(
                    "results add up until shown",
                    UploadResult(uploaded = 2, files = 3, failures = listOf("HTTP 500")),
                    queue.result.value,
                )
                queue.consumeResult()
                assertNull(queue.result.value)

                queue.submit(ids("a"))
                queue.passFinished(UploadResult(uploaded = 0, files = 1))
                sessions.signOut()
                withTimeout(5_000) { queue.activity.first { it == null } }
                assertNull(queue.result.value)
            } finally {
                scope.cancel()
            }
        }

    @Test fun `foreground refusal finishes released batches and permits another upload`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val queue = UploadQueue(SessionStore(Fixed(credentials)), library, scope)
                queue.submit(ids("a"))
                queue.submit(ids("b"))
                queue.release()
                var attempts = 0

                val entered = queue.enterForeground("foreground refused") {
                    attempts++
                    throw IllegalStateException("dataSync quota exhausted")
                }

                assertFalse(entered)
                assertEquals(1, attempts)
                assertNull(queue.next())
                assertTrue(queue.waiting.value.isEmpty())
                assertNull("a refused upload must not keep logout blocked", queue.activity.value)
                assertEquals(
                    UploadResult(0, 2, failures = listOf("foreground refused")),
                    queue.result.value,
                )
                queue.consumeResult()
                queue.submit(ids("a"))
                queue.release()
                assertTrue(queue.enterForeground("foreground refused") { attempts++ })
                assertEquals(2, attempts)
                assertEquals(files("a"), queue.next()?.files)
                assertNotNull(queue.activity.value)
                assertNull(queue.result.value)
            } finally {
                scope.cancel()
            }
        }

    @Test fun `foreground permission refusal preserves newer waiting work and previous result`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val queue = UploadQueue(SessionStore(Fixed(credentials)), library, scope)
                queue.passFinished(UploadResult(1, 1))
                queue.submit(ids("a", "b"))
                queue.release()
                queue.submit(ids("c"))

                assertFalse(
                    queue.enterForeground("foreground refused") { throw SecurityException() },
                )

                assertNull(queue.next())
                assertEquals(listOf(files("c")), queue.waiting.value.map { it.files })
                assertEquals(setOf("content://media/c"), queue.activity.value?.pending)
                assertEquals(
                    UploadResult(1, 3, failures = listOf("foreground refused")),
                    queue.result.value,
                )
                queue.release()
                assertTrue(queue.enterForeground("foreground refused") {})
                assertEquals(files("c"), queue.next()?.files)
            } finally {
                scope.cancel()
            }
        }

    private fun ids(vararg names: String) = names.map(::MediaId)

    private fun files(vararg names: String) =
        names.map { UploadSelection("content://media/$it", "$it.jpg", "image/jpeg") }

    private fun local(name: String) = MediaAssetRef.LocalContent("content://media/$name", 1)

    private fun item(name: String, asset: MediaAssetRef) =
        MediaItem(
            mediaId = MediaId(name),
            dayId = 1,
            displayName = "$name.jpg",
            mimeType = "image/jpeg",
            width = null,
            height = null,
            etag = null,
            livePhotoId = null,
            auid = null,
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = 1,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = asset,
        )

    private class Recording : UploadFollowUp {
        override suspend fun uploaded(credentials: AccountCredentials, file: UploadedFile) = Unit

        override suspend fun finished(missed: List<UploadSelection>) = emptyList<UiText>()
    }

    private class Fixed(private val credentials: AccountCredentials) : CredentialsStore {
        override fun load() = credentials
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    }
}

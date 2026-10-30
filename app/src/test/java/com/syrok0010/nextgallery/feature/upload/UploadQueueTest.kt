package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.ui.UiText
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

    @Test fun `released batches go to the service in order while newer ones keep waiting`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val queue = UploadQueue(SessionStore(Fixed(credentials)), scope)
                val first = batch("a")
                val second = batch("b")
                queue.submit(first)
                queue.submit(UploadBatch(emptyList()))
                queue.submit(second)
                assertEquals(listOf(first.files, second.files), queue.release().map { it.files })
                val third = batch("c")
                queue.submit(third)

                assertEquals(2, queue.releasedFiles())
                assertEquals(first.files, queue.next()?.files)
                assertEquals(
                    "waiting batches join the total only once released",
                    1,
                    queue.releasedFiles(),
                )
                assertEquals(second.files, queue.next()?.files)
                assertNull(queue.next())
                assertEquals(listOf(third.files), queue.waiting.value.map { it.files })
            } finally {
                scope.cancel()
            }
        }

    @Test fun `every batch gets the common follow-ups before its own`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val common = mutableListOf<UploadFollowUp>()
                val queue = UploadQueue(SessionStore(Fixed(credentials)), scope) {
                    listOf(Recording()).also(common::addAll)
                }
                val own = Recording()
                queue.submit(UploadBatch(batch("a").files, listOf(own)))
                queue.submit(batch("b"))
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
                val queue = UploadQueue(SessionStore(Fixed(credentials)), scope)
                val running = batch("a")
                val refused = batch("b")
                queue.submit(running)
                queue.submit(refused)
                val released = queue.release()
                // A service that was already running took one batch before the new start was refused.
                assertEquals(running.files, queue.next()?.files)
                val newer = batch("c")
                queue.submit(newer)

                queue.restore(released)

                assertEquals(
                    listOf(refused.files, newer.files),
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
                val queue = UploadQueue(sessions, scope)
                queue.submit(batch("a"))
                queue.release()
                queue.submit(batch("b"))
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
                val queue = UploadQueue(sessions, scope)
                val first = UploadBatch(batch("a").files + batch("b").files)
                queue.submit(first)
                queue.uploading("content://media/a", 40)
                queue.submit(batch("c"))
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
                assertNull(queue.activity.value)

                queue.passFinished(
                    UploadResult(uploaded = 1, files = 2, failures = listOf("HTTP 500")),
                )
                queue.passFinished(UploadResult(uploaded = 1, files = 1))
                assertEquals(
                    "results add up until shown",
                    UploadResult(uploaded = 2, files = 3, failures = listOf("HTTP 500")),
                    queue.result.value,
                )
                queue.consumeResult()
                assertNull(queue.result.value)

                queue.submit(batch("d"))
                queue.passFinished(UploadResult(uploaded = 0, files = 1))
                sessions.signOut()
                withTimeout(5_000) { queue.activity.first { it == null } }
                assertNull(queue.result.value)
            } finally {
                scope.cancel()
            }
        }

    private fun batch(name: String) =
        UploadBatch(listOf(UploadSelection("content://media/$name", "$name.jpg", "image/jpeg")))

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

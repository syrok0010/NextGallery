package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
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
                assertEquals(listOf(first, second), queue.release())
                val third = batch("c")
                queue.submit(third)

                assertEquals(2, queue.releasedFiles())
                assertSame(first, queue.next())
                assertEquals(
                    "waiting batches join the total only once released",
                    1,
                    queue.releasedFiles(),
                )
                assertSame(second, queue.next())
                assertNull(queue.next())
                assertEquals(listOf(third), queue.waiting.value)
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
                assertSame(running, queue.next())
                val newer = batch("c")
                queue.submit(newer)

                queue.restore(released)

                assertEquals(listOf(refused, newer), queue.waiting.value)
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

    private fun batch(name: String) =
        UploadBatch(listOf(UploadSelection("content://media/$name", "$name.jpg", "image/jpeg")))

    private class Fixed(private val credentials: AccountCredentials) : CredentialsStore {
        override fun load() = credentials
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    }
}

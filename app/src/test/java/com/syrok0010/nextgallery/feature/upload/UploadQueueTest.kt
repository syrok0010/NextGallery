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
                assertEquals(2, queue.release())
                val third = batch("c")
                queue.submit(third)

                assertSame(first, queue.next())
                assertSame(second, queue.next())
                assertNull(queue.next())
                assertEquals(listOf(third), queue.waiting.value)
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

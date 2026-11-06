package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.core.network.capabilities.NextcloudCapabilitiesClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.albums.MemoriesAlbumWriterTest.Fixture
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudTrashAvailabilityTest {
    @Test fun `only an explicit undelete in capabilities confirms trash`() =
        runBlocking {
            var body = ""
            Fixture { 200 to body }.use { fixture ->
                val trashBin =
                    NextcloudTrashBin(NextcloudCapabilitiesClient(fixture.writerTransport))
                suspend fun available(response: String): Boolean? {
                    body = response
                    return trashBin.available(fixture.credentials)
                }
                assertEquals(true, available(capabilities("""{"undelete":true}""")))
                assertEquals(false, available(capabilities("""{"undelete":false}""")))
                assertNull(available(capabilities("""{"undelete":null}""")))
                // With files_trashbin disabled the stand omits undelete instead of sending false.
                assertNull(available(capabilities("""{"versioning":true}""")))
                assertNull(available("""{"ocs":{"data":{"capabilities":{}}}}"""))
                listOf("1", "{}", "[]").forEach { value ->
                    assertTrue(
                        runCatching { available(capabilities("""{"undelete":$value}""")) }
                            .isFailure,
                    )
                }
                listOf("{}", "[]", "null", "not JSON").forEach { response ->
                    assertTrue(runCatching { available(response) }.isFailure)
                }
            }
        }

    @Test fun `login loads trash and refresh drops an old promise before failure`() =
        runTest {
            val sessions = sessions()
            val gate = CompletableDeferred<Boolean?>()
            var calls = 0
            val repository = CloudTrashAvailability(sessions, backgroundScope) {
                when (++calls) {
                    1 -> true
                    2 -> gate.await()
                    else -> throw IOException("offline")
                }
            }
            runCurrent()
            assertNull(repository.available.value)
            sessions.signIn(account)
            runCurrent()
            assertEquals(true, repository.available.value)
            repository.refresh()
            assertNull(repository.available.value)
            runCurrent()
            gate.complete(false)
            runCurrent()
            assertEquals(false, repository.available.value)
            repository.refresh()
            runCurrent()
            assertNull(repository.available.value)
            assertEquals(3, calls)
        }

    @Test fun `account replacement cancels old request and logout forgets capabilities`() =
        runTest {
            val sessions = sessions()
            sessions.signIn(account)
            var cancelled = false
            val repository = CloudTrashAvailability(sessions, backgroundScope) { credentials ->
                if (credentials == account) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                } else {
                    false
                }
            }
            runCurrent()
            sessions.signIn(account.copy(userId = "other", loginName = "other"))
            runCurrent()
            assertTrue(cancelled)
            assertEquals(false, repository.available.value)
            sessions.signOut()
            runCurrent()
            assertNull(repository.available.value)
        }

    private fun sessions() =
        SessionStore(object : CredentialsStore {
            override fun load(): AccountCredentials? = null
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        })

    private val account = AccountCredentials("https://cloud.example/", "test", "password", "test")

    private fun capabilities(files: String) =
        """{"ocs":{"meta":{"statuscode":200},"data":{"capabilities":{"files":$files}}}}"""
}

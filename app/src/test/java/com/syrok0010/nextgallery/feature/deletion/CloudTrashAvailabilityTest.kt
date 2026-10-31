package com.syrok0010.nextgallery.feature.deletion

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
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudTrashAvailabilityTest {
    @Test fun `only an explicit boolean in successful OCS capabilities confirms trash`() {
        assertEquals(true, cloudTrashAvailable(capabilities("true")))
        assertEquals(false, cloudTrashAvailable(capabilities("false")))
        assertEquals(true, cloudTrashAvailable(capabilities("true", 100)))
        listOf("null", "1", "0", "\"true\"", "{}", "[]").forEach { value ->
            assertNull(cloudTrashAvailable(capabilities(value)))
        }
        assertNull(cloudTrashAvailable(capabilities("true", 997)))
        // With files_trashbin disabled the stand omits undelete instead of sending false.
        assertNull(
            cloudTrashAvailable(
                """{"ocs":{"meta":{"statuscode":200},"data":{"capabilities":{"files":{"versioning":true}}}}}""",
            ),
        )
        listOf("{}", "[]", "null", "not JSON").forEach {
            assertNull(cloudTrashAvailable(it))
        }
    }

    @Test fun `capabilities request is authenticated and HTTP errors cannot confirm trash`() =
        runBlocking {
            var status = 200
            Fixture { status to capabilities("true") }.use { fixture ->
                val source = NextcloudTrashBin(fixture.writerTransport)
                assertEquals(true, source.available(fixture.credentials))
                val request = fixture.requests.single()
                assertEquals("GET", request.method)
                assertEquals("/ocs/v2.php/cloud/capabilities?format=json", request.target)
                assertEquals("true", request.headers["ocs-apirequest"])
                assertEquals(
                    Credentials.basic("fixture", "password"),
                    request.headers["authorization"],
                )
                status = 503
                assertNull(source.available(fixture.credentials))
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

    private fun capabilities(undelete: String, status: Int = 200) =
        """{"ocs":{"meta":{"statuscode":$status},"data":{"capabilities":{"files":{"undelete":$undelete}}}}}"""
}

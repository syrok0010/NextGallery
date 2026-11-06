package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.session.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AlbumRemovalsTest {
    @Test fun `rejected repeated request keeps running removal and terminal failures survive next operation`() = runBlocking {
        val release = CountDownLatch(1)
        MemoriesAlbumWriterTest.Fixture { request ->
            if (request.method == "PROPFIND") {
                check(release.await(5, TimeUnit.SECONDS))
                207 to MemoriesAlbumRemoverTest.listing("/remote.php/dav/photos/anna/albums/album")
            } else {
                (if (request.decodedTarget.endsWith("2-b.jpg")) 403 else 204) to ""
            }
        }.use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val sessions = sessions(server.credentials)
                var refreshed = 0
                val removals = AlbumRemovals(sessions, MemoriesAlbumRemover(server.writerTransport), scope) { refreshed++ }
                val location = AlbumLocation.Remote("anna/album")
                assertTrue(removals.remove(location, "album", setOf(1, 2)))
                assertEquals(setOf(1L, 2L), removals.progress.value?.fileIds)
                assertFalse(removals.remove(location, "album", setOf(3)))
                release.countDown()
                withTimeout(5000) { removals.result.filterNotNull().first() }
                withTimeout(5000) { removals.progress.first { it == null } }
                assertEquals(AlbumRemovalResult(1, 1), removals.result.value?.result)
                assertEquals(1, refreshed)
                assertTrue(removals.remove(location, "album", setOf(3)))
                withTimeout(5000) { removals.progress.first { it == null } }
                assertEquals(AlbumRemovalResult(2, 1), removals.result.value?.result)
                assertEquals(2, refreshed)
                removals.consume()
                assertNull(removals.result.value)
                assertTrue(removals.remove(location, "album", setOf(1)))
                sessions.signOut()
                assertNull(removals.progress.value)
                assertNull(removals.result.value)
                assertFalse(removals.remove(location, "album", setOf(1)))
            } finally {
                release.countDown()
                scope.cancel()
            }
        }
    }

    private fun sessions(credentials: AccountCredentials) = SessionStore(object : CredentialsStore {
        override fun load() = credentials
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    })
}

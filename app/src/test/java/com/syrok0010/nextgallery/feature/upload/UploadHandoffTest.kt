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

class UploadHandoffTest {
    private val credentials = AccountCredentials("http://cloud", "anna", "secret", "anna")

    @Test fun `screen takes the snapshot it started while newer offers stay pending`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val handoff = UploadHandoff(SessionStore(Fixed(credentials)), scope)
                handoff.offer(listOf(selection("a")))
                val started = handoff.pending.value
                handoff.offer(listOf(selection("b")))
                handoff.taken(started)
                assertEquals(listOf(selection("b")), handoff.pending.value)
            } finally {
                scope.cancel()
            }
        }

    @Test fun `logout drops pending uploads`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val sessions = SessionStore(Fixed(credentials))
                val handoff = UploadHandoff(sessions, scope)
                handoff.offer(listOf(selection("a")))
                yield()
                sessions.signOut()
                withTimeout(5_000) { handoff.pending.first { it.isEmpty() } }
                Unit
            } finally {
                scope.cancel()
            }
        }

    private fun selection(name: String) =
        UploadSelection(
            "content://media/$name",
            "$name.jpg",
            "image/jpeg",
            UploadAlbum("anna", "Лето"),
        )

    private class Fixed(private val credentials: AccountCredentials) : CredentialsStore {
        override fun load() = credentials
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    }
}

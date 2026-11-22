package com.syrok0010.nextgallery.di

import com.syrok0010.nextgallery.app.library.LibraryPublicationStore
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.AuthenticatedSession
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.scope.Scope

/** Process-owned graph: leaving the UI must not stop uploads or indexing. */
internal class AuthenticatedGraph(private val koin: Koin) : AutoCloseable {
    @Volatile private var current: Scope? = null

    @Synchronized
    fun start(credentials: AccountCredentials) {
        current?.let {
            check(
                it.get<AuthenticatedSession>().credentials == credentials,
            ) { "Authenticated graph is already running" }
            return
        }
        val session = AuthenticatedSession(credentials)
        val scope = koin.createScope<AuthenticatedSession>("authenticated", session)
        try {
            scope.get<UploadQueue>()
            val coordinator = scope.get<MediaLibraryCoordinator>()
            val publication = scope.get<LibraryPublicationStore>()
            scope.get<CoroutineScope>().launch { coordinator.run(publication::publish) }
            current = scope
        } catch (error: Throwable) {
            scope.close()
            throw error
        }
    }

    @Synchronized
    fun startIfSignedIn(sessions: SessionStore): Scope? {
        val signedIn = sessions.session.value as? SessionUiState.SignedIn ?: return null
        start(signedIn.credentials)
        return current
    }

    @Synchronized
    fun requireScope(): Scope = checkNotNull(current) { "Authenticated graph has not started" }

    @Synchronized
    override fun close() {
        current?.takeUnless { it.closed }?.close()
        current = null
    }
}

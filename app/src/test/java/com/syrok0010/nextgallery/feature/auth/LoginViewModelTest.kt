package com.syrok0010.nextgallery.feature.auth

import com.syrok0010.nextgallery.core.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @Test fun `failed credential storage keeps session signed out and allows new login`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var fail = true
            val credentials = AccountCredentials("https://cloud.example", "user", "password", "user")
            val store = object : CredentialsStore {
                override fun load(): AccountCredentials? = null
                override fun save(credentials: AccountCredentials) { if (fail) error("keystore unavailable") }
                override fun clear() = Unit
            }
            val session = SessionStore(store)
            val gateway = object : LoginGateway {
                override suspend fun checkMemories(credentials: AccountCredentials) = MemoriesCompatibility.Supported
                override suspend fun startLogin(serverUrl: String) = LoginSession(serverUrl, "https://login", "https://poll", "token")
                override suspend fun pollLogin(session: LoginSession) = LoginPollResult.Ready(credentials)
            }
            val vm = LoginViewModel(session, store, gateway, dispatcher)
            vm.updateServerUrl(credentials.serverUrl)
            vm.startLogin()
            advanceUntilIdle()
            assertEquals(SessionUiState.SignedOut, session.session.value)
            assertEquals(LoginAttempt.Failed, vm.state.value.login.attempt)
            assertNotNull(vm.state.value.message.error)
            assertFalse(vm.state.value.isBusy)
            fail = false
            vm.startLogin()
            advanceUntilIdle()
            assertEquals(SessionUiState.SignedIn(credentials), session.session.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `cancelling an awaiting login prevents polling and cannot leave impossible flags`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val store = object : CredentialsStore {
                override fun load(): AccountCredentials? = null
                override fun save(credentials: AccountCredentials) = error("unexpected save")
                override fun clear() = Unit
            }
            var polls = 0
            val gateway = object : LoginGateway {
                override suspend fun checkMemories(credentials: AccountCredentials) = MemoriesCompatibility.Supported
                override suspend fun startLogin(serverUrl: String) = LoginSession(serverUrl, "login", "poll", "token")
                override suspend fun pollLogin(session: LoginSession): LoginPollResult { polls++; return LoginPollResult.Pending }
            }
            val vm = LoginViewModel(SessionStore(store), store, gateway, dispatcher)
            vm.updateServerUrl("https://cloud.example")
            vm.startLogin()
            runCurrent()
            assertTrue(vm.state.value.login.isPolling)
            vm.cancelLogin()
            advanceUntilIdle()
            assertEquals(0, polls)
            assertNull(vm.state.value.login.session)
            assertFalse(vm.state.value.login.isPolling)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `recoverable polling failures stay awaiting until confirmation timeout`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val store = object : CredentialsStore {
                override fun load(): AccountCredentials? = null
                override fun save(credentials: AccountCredentials) = error("unexpected save")
                override fun clear() = Unit
            }
            var polls = 0
            val gateway = object : LoginGateway {
                override suspend fun checkMemories(credentials: AccountCredentials) = MemoriesCompatibility.Supported
                override suspend fun startLogin(serverUrl: String) = LoginSession(serverUrl, "login", "poll", "token")
                override suspend fun pollLogin(session: LoginSession): LoginPollResult {
                    polls++
                    return LoginPollResult.Failed(LoginPollFailure.Network, isRecoverable = true)
                }
            }
            val session = SessionStore(store)
            val vm = LoginViewModel(session, store, gateway, dispatcher)
            vm.updateServerUrl("https://cloud.example")
            vm.startLogin()
            advanceTimeBy(4_001)
            runCurrent()
            assertEquals(2, polls)
            assertTrue(vm.state.value.login.isPolling)
            advanceUntilIdle()
            assertEquals(LoginAttempt.Failed, vm.state.value.login.attempt)
            assertEquals(SessionUiState.SignedOut, session.session.value)
            assertNotNull(vm.state.value.message.error)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `blocked login does not save credentials and retry checks without polling spent token`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            for (failure in listOf(MemoriesCompatibility.TooOld("8.1.0"), MemoriesCompatibility.Unavailable, MemoriesCompatibility.UnknownVersion, MemoriesCompatibility.NetworkError, MemoriesCompatibility.CheckFailed)) {
                val store = MemoryStore()
                val sessions = SessionStore(store)
                val gateway = CompatibilityGateway(failure)
                val vm = LoginViewModel(sessions, store, gateway, dispatcher)
                vm.updateServerUrl("https://cloud.example")
                vm.startLogin()
                advanceUntilIdle()
                assertEquals(SessionUiState.SignedOut, sessions.session.value)
                assertNull(store.saved)
                assertEquals(LoginAttempt.MemoriesBlocked, vm.state.value.login.attempt)
                assertNotNull(vm.state.value.message.error)
                gateway.compatibility = MemoriesCompatibility.Supported
                vm.retryMemoriesCheck()
                advanceUntilIdle()
                assertEquals(1, gateway.polls)
                assertEquals(2, gateway.checks)
                assertEquals(gateway.credentials, store.saved)
                assertEquals(SessionUiState.SignedIn(gateway.credentials), sessions.session.value)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `exit during verification discards credentials and prevents a later sign in`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val store = MemoryStore()
            val sessions = SessionStore(store)
            val gateway = CompatibilityGateway(MemoriesCompatibility.Supported, waitForCheck = true)
            val vm = LoginViewModel(sessions, store, gateway, dispatcher)
            vm.updateServerUrl("https://cloud.example")
            vm.startLogin()
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(LoginAttempt.CheckingMemories, vm.state.value.login.attempt)
            vm.cancelLogin()
            gateway.release.complete(Unit)
            advanceUntilIdle()
            vm.retryMemoriesCheck()
            advanceUntilIdle()
            assertEquals(1, gateway.checks)
            assertNull(store.saved)
            assertEquals(SessionUiState.SignedOut, sessions.session.value)
            assertEquals(LoginAttempt.Idle, vm.state.value.login.attempt)
        } finally { Dispatchers.resetMain() }
    }

    private class MemoryStore : CredentialsStore {
        var saved: AccountCredentials? = null
        override fun load(): AccountCredentials? = saved
        override fun save(credentials: AccountCredentials) { saved = credentials }
        override fun clear() { saved = null }
    }

    private class CompatibilityGateway(var compatibility: MemoriesCompatibility, val waitForCheck: Boolean = false) : LoginGateway {
        val credentials = AccountCredentials("https://cloud.example", "test", "password", "test")
        var polls = 0
        var checks = 0
        val release = CompletableDeferred<Unit>()
        override suspend fun startLogin(serverUrl: String) = LoginSession(serverUrl, "login", "poll", "token")
        override suspend fun pollLogin(session: LoginSession): LoginPollResult {
            polls++
            return LoginPollResult.Ready(credentials)
        }
        override suspend fun checkMemories(credentials: AccountCredentials): MemoriesCompatibility {
            checks++
            if (waitForCheck) release.await()
            return compatibility
        }
    }

}

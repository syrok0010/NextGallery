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
            val credentials = AccountCredentials("https://cloud.example", "user", "password")
            val store = object : CredentialsStore {
                override fun load(): AccountCredentials? = null
                override fun save(credentials: AccountCredentials) { if (fail) error("keystore unavailable") }
                override fun clear() = Unit
            }
            val session = SessionStore(store)
            val gateway = object : LoginGateway {
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

}

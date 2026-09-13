package com.syrok0010.nextgallery.feature.albums

import androidx.lifecycle.ViewModelStore
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import com.syrok0010.nextgallery.feature.timeline.TimelineScreenState
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumContentsViewModelTest {
    @Test fun `changing albums cancels pending load and errors can be retried`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val permission = MutableStateFlow<LocalMediaPermissionMode?>(null)
            var cancelled = false
            var fail = true
            val first = AlbumLocation.Remote("anna/first")
            val second = AlbumLocation.Remote("anna/second")
            val vm = AlbumContentsViewModel(sessions(), AlbumContentsSource { location, _ -> flow {
                if (location == first) {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                if (fail) error("offline")
                emit(AlbumContentsBatch(emptyList(), 0))
            } }, MutableStateFlow(TimelineScreenState()), permission)
            store.put("vm", vm)
            vm.select(first); runCurrent(); assertTrue(vm.state.value.loading)
            vm.select(second); runCurrent()
            assertTrue(cancelled); assertTrue(vm.state.value.failed)
            assertEquals(second, vm.state.value.location)
            fail = false; vm.refresh(); runCurrent()
            assertFalse(vm.state.value.failed); assertFalse(vm.state.value.loading)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `permission revocation and logout cancel folder loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val permission = MutableStateFlow<LocalMediaPermissionMode?>(null)
            var calls = 0
            var cancellations = 0
            val sessions = sessions()
            val vm = AlbumContentsViewModel(sessions, AlbumContentsSource { _, _ -> flow {
                calls++
                emit(AlbumContentsBatch(emptyList(), 42))
                try { awaitCancellation() } finally { cancellations++ }
            } }, MutableStateFlow(TimelineScreenState()), permission)
            store.put("vm", vm)
            vm.select(AlbumLocation.Folder("external_primary", "Pictures/Trip/")); runCurrent()
            assertEquals(0, calls); assertTrue(vm.state.value.permissionRequired)
            permission.value = LocalMediaPermissionMode.Full; runCurrent()
            assertEquals(42, vm.state.value.total)
            permission.value = LocalMediaPermissionMode.Partial; runCurrent()
            assertEquals(1, cancellations); assertEquals(0, vm.state.value.total)
            assertTrue(vm.state.value.permissionRequired)
            permission.value = LocalMediaPermissionMode.Full; runCurrent()
            sessions.signOut(); runCurrent()
            assertEquals(2, cancellations); assertEquals(0, vm.state.value.total)
            assertFalse(vm.state.value.loading)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    private fun sessions() = SessionStore(object : CredentialsStore {
        override fun load() = AccountCredentials("https://cloud.example", "demo", "secret")
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    })
}

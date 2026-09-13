package com.syrok0010.nextgallery.feature.albums

import androidx.lifecycle.ViewModelStore
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsViewModelTest {
    @Test fun `remote failure preserves local results and refresh recovers independently`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val sessions = sessions()
            var fail = true
            val remote = object : RemoteAlbumSource {
                override suspend fun load(credentials: AccountCredentials): RemoteAlbums {
                    if (fail) error("offline")
                    return RemoteAlbums(listOf(album("remote", AlbumOrigin.Nextcloud)))
                }
            }
            val vm = AlbumsViewModel(sessions, remote, local())
            store.put("vm", vm)
            vm.updatePermission(LocalMediaPermissionMode.Full)
            runCurrent()
            assertTrue(vm.state.value.remote.failed)
            assertEquals(listOf("local"), vm.state.value.items.map { it.id })
            fail = false
            vm.refresh()
            runCurrent()
            assertEquals(2, vm.state.value.items.size)
            assertFalse(vm.state.value.remote.failed)
            vm.updatePermission(LocalMediaPermissionMode.Partial)
            runCurrent()
            assertTrue(vm.state.value.local.items.isEmpty())
            assertEquals(1, vm.state.value.remote.items.size)
            sessions.signOut()
            runCurrent()
            assertTrue(vm.state.value.items.isEmpty())
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun `sign out cancels in flight source and prevents stale account publication`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val gate = CompletableDeferred<Unit>()
            val sessions = sessions()
            val remote = object : RemoteAlbumSource {
                override suspend fun load(credentials: AccountCredentials): RemoteAlbums {
                    gate.await()
                    return RemoteAlbums(listOf(album("old-account", AlbumOrigin.Nextcloud)))
                }
            }
            val vm = AlbumsViewModel(sessions, remote, local()); store.put("vm", vm)
            runCurrent(); assertTrue(vm.state.value.remote.loading)
            sessions.signOut(); runCurrent(); gate.complete(Unit); runCurrent()
            assertTrue(vm.state.value.items.isEmpty())
            assertFalse(vm.state.value.remote.loading)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    private fun local() = object : LocalAlbumSource {
        override suspend fun load() = listOf(album("local", AlbumOrigin.Phone))
        override fun changes() = emptyFlow<Unit>()
    }
    private fun album(id: String, origin: AlbumOrigin) = AlbumSummary(id, "Отпуск", origin, 1, "")
    private fun sessions() = SessionStore(object : CredentialsStore {
        override fun load() = AccountCredentials("https://cloud.example", "demo", "secret")
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    })
}

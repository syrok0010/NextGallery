package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumCatalogRepositoryTest {
    @Test fun `remote failure preserves local results and refresh recovers independently`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                var fail = true
                var localLoads = 0
                val remote = object : RemoteAlbumSource {
                    override suspend fun load(): RemoteAlbums {
                        if (fail) error("offline")
                        return RemoteAlbums(listOf(album("remote", AlbumOrigin.Nextcloud)))
                    }
                }
                val local = object : LocalAlbumSource {
                    override suspend fun load(): List<AlbumSummary> {
                        localLoads++
                        return listOf(album("local", AlbumOrigin.Phone))
                    }

                    override fun changes() = emptyFlow<Unit>()
                }
                val permission = MutableStateFlow<LocalMediaPermissionMode?>(null)
                val vm = AlbumCatalogRepository(remote, local, permission, backgroundScope)

                permission.value = LocalMediaPermissionMode.Full
                runCurrent()
                assertTrue(vm.state.value.remote.failed)
                assertEquals(listOf("local"), vm.state.value.items.map { it.id })
                assertEquals(1, localLoads)
                fail = false
                vm.refresh()
                runCurrent()
                assertEquals(2, vm.state.value.items.size)
                assertFalse(vm.state.value.remote.failed)
                assertEquals(2, localLoads)
                permission.value = LocalMediaPermissionMode.Partial
                runCurrent()
                assertTrue(vm.state.value.local.items.isEmpty())
                assertEquals(1, vm.state.value.remote.items.size)
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun album(id: String, origin: AlbumOrigin) = AlbumSummary(id, "Отпуск", origin, 1, "")
}

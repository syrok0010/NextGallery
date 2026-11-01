package com.syrok0010.nextgallery.feature.albums

import androidx.lifecycle.ViewModelStore
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.feature.collection.ViewerSequenceController
import com.syrok0010.nextgallery.feature.collection.toMediaSlots
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesPhotoDto
import com.syrok0010.nextgallery.feature.timeline.remote.toMediaItem
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumContentsViewModelTest {
    @Test fun `album scope cancels pending load and errors can be retried`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = ViewModelStore()
            try {
                val permission = MutableStateFlow<LocalMediaPermissionMode?>(null)
                var cancelled = false
                var fail = true
                val first = AlbumLocation.Remote("anna/first")
                val second = AlbumLocation.Remote("anna/second")
                val source = AlbumContentsSource { location, _ ->
                    flow {
                        if (location == first) {
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled = true
                            }
                        }
                        if (fail) error("offline")
                        emit(AlbumContentsBatch(emptyList(), 0))
                    }
                }
                val firstViewModel = AlbumContentsViewModel(
                    sessions(),
                    source,
                    MediaLibraryIndex(),
                    permission,
                    first,
                )
                store.put("first", firstViewModel)
                runCurrent()
                assertTrue(firstViewModel.state.value.loading)
                store.clear()
                runCurrent()
                assertTrue(cancelled)

                val secondViewModel = AlbumContentsViewModel(
                    sessions(),
                    source,
                    MediaLibraryIndex(),
                    permission,
                    second,
                )
                store.put("second", secondViewModel)
                runCurrent()
                assertTrue(secondViewModel.state.value.failed)
                fail = false
                secondViewModel.refresh()
                runCurrent()
                assertFalse(secondViewModel.state.value.failed)
                assertFalse(secondViewModel.state.value.loading)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }

    @Test fun `permission revocation and logout cancel folder loading`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = ViewModelStore()
            try {
                val permission = MutableStateFlow<LocalMediaPermissionMode?>(null)
                var calls = 0
                var cancellations = 0
                val sessions = sessions()
                val vm = AlbumContentsViewModel(
                    sessions,
                    AlbumContentsSource { _, _ ->
                        flow {
                            calls++
                            emit(AlbumContentsBatch(emptyList(), 42))
                            try {
                                awaitCancellation()
                            } finally {
                                cancellations++
                            }
                        }
                    },
                    MediaLibraryIndex(),
                    permission,
                    AlbumLocation.Folder("external_primary", "Pictures/Trip/"),
                )
                store.put("vm", vm)
                runCurrent()
                assertEquals(0, calls)
                assertTrue(vm.state.value.permissionRequired)
                permission.value = LocalMediaPermissionMode.Full
                runCurrent()
                assertEquals(42, vm.state.value.total)
                permission.value = LocalMediaPermissionMode.Partial
                runCurrent()
                assertEquals(1, cancellations)
                assertEquals(0, vm.state.value.total)
                assertTrue(vm.state.value.permissionRequired)
                permission.value = LocalMediaPermissionMode.Full
                runCurrent()
                sessions.signOut()
                runCurrent()
                assertEquals(2, cancellations)
                assertEquals(0, vm.state.value.total)
                assertFalse(vm.state.value.loading)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
    @Test fun `refresh commits complete membership atomically and viewer releases departed current`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val first = MemoriesPhotoDto(1, 20, epoch = 1_728_000).toMediaItem(MediaId("first"))
            val second = MemoriesPhotoDto(2, 20, epoch = 1_728_001).toMediaItem(MediaId("second"))
            var members = listOf(first, second)
            var fail = false
            val release = MutableStateFlow(true)
            val changes = MutableStateFlow(0)
            val vm = AlbumContentsViewModel(
                sessions(), AlbumContentsSource { _, _ -> flow {
                    emit(AlbumContentsBatch(emptyList(), members.size))
                    release.first { it }
                    if (fail) error("offline after first page")
                    emit(AlbumContentsBatch(members, members.size))
                } }, MediaLibraryIndex(), MutableStateFlow(LocalMediaPermissionMode.Full),
                AlbumLocation.Remote("demo/album"), changes.map { Unit },
            )
            store.put("vm", vm)
            runCurrent()
            val viewer = ViewerSequenceController()
            viewer.updateSlots(vm.state.value.items.toMediaSlots(), first.mediaId)
            release.value = false
            members = listOf(second)
            changes.value++
            runCurrent()
            assertTrue(vm.state.value.loading)
            assertEquals(setOf(first.mediaId, second.mediaId), vm.state.value.items.map { it.mediaId }.toSet())
            assertTrue(vm.state.value.departedIds.isEmpty())
            release.value = true
            runCurrent()
            assertEquals(listOf(second), vm.state.value.items)
            assertEquals(setOf(first.mediaId), vm.state.value.departedIds)
            viewer.updateSlots(vm.state.value.items.toMediaSlots(), first.mediaId, vm.state.value.departedIds)
            assertEquals(second.mediaId, viewer.state.replacementMediaId)
            assertEquals(listOf(second), viewer.state.sequence.items)
            // A failed refresh never commits a partial empty membership or closes the viewer.
            fail = true
            vm.refresh()
            runCurrent()
            assertTrue(vm.state.value.failed)
            assertEquals(listOf(second), vm.state.value.items)
            fail = false
            members = emptyList()
            vm.refresh()
            runCurrent()
            viewer.updateSlots(vm.state.value.items.toMediaSlots(), second.mediaId, vm.state.value.departedIds)
            assertTrue(viewer.state.sequence.items.isEmpty())
            // Returning membership clears its old departure, so later selection remains valid.
            members = listOf(first)
            vm.refresh()
            runCurrent()
            assertFalse(first.mediaId in vm.state.value.departedIds)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun sessions() =
        SessionStore(object : CredentialsStore {
            override fun load() =
                AccountCredentials("https://cloud.example", "demo", "secret", "demo")
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        })
}

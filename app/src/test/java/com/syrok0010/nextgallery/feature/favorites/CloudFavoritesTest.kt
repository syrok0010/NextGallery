package com.syrok0010.nextgallery.feature.favorites

import com.syrok0010.nextgallery.core.media.*
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.library.*
import com.syrok0010.nextgallery.feature.timeline.remote.*
import com.syrok0010.nextgallery.feature.collection.toMediaSlots
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class CloudFavoritesTest {
    private val credentials = AccountCredentials("https://example.test", "test", "secret", "test")
    private fun sessions() = SessionStore(object : CredentialsStore {
        override fun load() = credentials
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    })
    private fun item(id: Long = 1, favorite: Boolean = false) = MediaItem(
        MediaId("media-$id"), 10, "photo.jpg", "image/jpeg", 10, 10, "etag", null, null, null, null,
        864000, false, null, favorite, false, MediaAssetRef.MemoriesFile(id),
    )

    @Test fun `pending keeps previous value blocks repeated taps and failure leaves metadata intact`() = runTest {
        val sessions = sessions()
        val metadata = CloudFavoriteMetadata(sessions)
        val reply = CompletableDeferred<Unit>()
        var writes = 0
        val favorites = CloudFavorites(sessions, metadata, { emptyList() }, MediaLibraryIndex(), backgroundScope,
            write = { _, _, _ -> writes++; reply.await(); throw IOException() }, persist = { fail("Cannot persist failed write") })
        runCurrent()
        assertTrue(favorites.set(item()))
        assertFalse(favorites.set(item()))
        runCurrent()
        assertEquals(FavoritePending(1, false), favorites.pending.value)
        assertTrue(metadata.values.value.isEmpty())
        reply.complete(Unit)
        runCurrent()
        assertEquals(1, writes)
        assertNull(favorites.pending.value)
        assertTrue(favorites.error.value)
        assertFalse(favoriteProjection(listOf(item()), metadata.values.value).single().isFavorite)
    }

    @Test fun `confirmed write persists remote copy and stale reads cannot undo memory or durable state`() = runTest {
        val sessions = sessions()
        val metadata = CloudFavoriteMetadata(sessions)
        val oldRead = metadata.begin(credentials)
        var disk = item()
        metadata.confirm(oldRead, disk, true) { disk = it }
        val stale = metadata.ingest(oldRead, listOf(item())) { disk = it.single() }
        assertTrue(stale.single().isFavorite)
        assertTrue(disk.isFavorite)
        assertTrue(favoriteProjection(listOf(item()), metadata.values.value).single().isFavorite)
        // A server refresh started after confirmation can observe another client's edit.
        val fresh = metadata.begin(credentials)
        metadata.ingest(fresh, listOf(item())) { disk = it.single() }
        assertFalse(disk.isFavorite)
        assertFalse(metadata.values.value.getValue(1).favorite)
    }

    @Test fun `late result from previous generation cannot write even after same account signs in`() = runTest {
        val sessions = sessions()
        val metadata = CloudFavoriteMetadata(sessions)
        val read = metadata.begin(credentials)
        sessions.signOut()
        sessions.signIn(credentials)
        metadata.begin(credentials)
        var saved = false
        try { metadata.confirm(read, item(), true) { saved = true }; fail("Old session accepted") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(saved)
        assertTrue(metadata.values.value.isEmpty())
    }

    @Test fun `navigation-independent operation confirms album-only item and local-only never writes`() = runTest {
        val sessions = sessions()
        val metadata = CloudFavoriteMetadata(sessions)
        var disk: MediaItem? = null
        val favorites = CloudFavorites(sessions, metadata, { emptyList() }, MediaLibraryIndex(), backgroundScope,
            write = { _, _, _ -> }, persist = { disk = it })
        runCurrent()
        assertFalse(favorites.set(item().copy(assetRef = MediaAssetRef.LocalContent("content://local/1", 1))))
        assertTrue(favorites.set(item().copy(assetRef = MediaAssetRef.LocalFirst(MediaAssetRef.LocalContent("content://local/1", 1), MediaAssetRef.MemoriesFile(1)))))
        runCurrent()
        assertTrue(requireNotNull(disk).isFavorite)
        assertTrue(disk?.assetRef is MediaAssetRef.MemoriesFile)
        assertEquals(listOf(MediaId("media-1")), favorites.state.value.items.map { it.mediaId })
        assertTrue(favorites.set(favorites.state.value.items.single()))
        runCurrent()
        assertTrue(favorites.state.value.items.isEmpty())
        assertEquals(setOf(MediaId("media-1")), favorites.state.value.departed)
    }

    @Test fun `merged projection follows cloud and excludes local-only regardless of local flag`() = runTest {
        val local = item().copy(assetRef = MediaAssetRef.LocalContent("content://local/1", 1), isFavorite = true)
        val merged = projectMediaLibrary(listOf(local), listOf(item(favorite = false)))
        assertFalse(merged.items.single().isFavorite)
        assertTrue(merged.items.single().hasLocalCopy)
        assertFalse(favoriteProjection(listOf(local), emptyMap()).single().isFavorite)
    }

    @Test fun `server favorite loader traverses every old incomplete day in bounded batches`() = runTest {
        val requested = mutableListOf<String>()
        val api = object : FavoritesApi {
            override suspend fun days(favorite: Int): List<MemoriesDayDto> {
                assertEquals(1, favorite)
                return (1..23).map { MemoriesDayDto(it, 1, if (it == 23) listOf(MemoriesPhotoDto(23, 23)) else emptyList()) }
            }
            override suspend fun photos(ids: String, favorite: Int): List<MemoriesPhotoDto> {
                assertEquals(1, favorite)
                requested += ids
                return ids.split(',').map { MemoriesPhotoDto(it.toLong(), it.toInt()) }
            }
        }
        val photos = loadFavoritePhotos(api)
        assertEquals(23, photos.size)
        assertTrue(photos.any { it.dayid == 1 })
        assertEquals(listOf(10, 10, 2), requested.map { it.split(',').size })
        assertFalse(requested.any { "23" in it.split(',') })
    }

    @Test fun `active favorite filter advances to neighbor then closes empty sequence`() {
        val controller = com.syrok0010.nextgallery.feature.collection.ViewerSequenceController()
        val first = item(1, true)
        val second = item(2, true)
        controller.updateSlots(listOf(first, second).toMediaSlots(), first.mediaId)
        controller.updateSlots(listOf(second).toMediaSlots(), first.mediaId, setOf(first.mediaId))
        assertEquals(second.mediaId, controller.state.replacementMediaId)
        assertFalse(first.mediaId in controller.state.sequence)
        controller.updateSlots(emptyList(), second.mediaId, setOf(first.mediaId, second.mediaId))
        assertTrue(controller.state.sequence.items.isEmpty())
        assertNull(controller.state.replacementMediaId)
    }

    @Test fun `complete query membership does not turn off favorite on album file outside timeline`() = runTest {
        val sessions = sessions()
        val metadata = CloudFavoriteMetadata(sessions)
        val favorites = CloudFavorites(sessions, metadata, { emptyList() }, MediaLibraryIndex(), backgroundScope,
            write = { _, _, _ -> }, persist = { })
        runCurrent()
        favorites.set(item())
        runCurrent()
        assertTrue(favorites.state.value.items.single().isFavorite)
        favorites.refresh()
        runCurrent()
        assertTrue(favorites.state.value.loaded)
        assertTrue(favorites.state.value.items.isEmpty())
        assertEquals(setOf(item().mediaId), favorites.state.value.departed)
        assertTrue(metadata.values.value.getValue(1).favorite)
    }

    @Test fun `late canceled operation cannot publish into replacement session`() = runTest {
        val sessions = sessions()
        val metadata = CloudFavoriteMetadata(sessions)
        val reply = CompletableDeferred<Unit>()
        var writesToDisk = 0
        val favorites = CloudFavorites(sessions, metadata, { emptyList() }, MediaLibraryIndex(), backgroundScope,
            write = { _, _, _ -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { reply.await() } },
            persist = { writesToDisk++ })
        runCurrent()
        assertTrue(favorites.set(item()))
        runCurrent()
        sessions.signOut()
        sessions.signIn(credentials)
        runCurrent()
        reply.complete(Unit)
        runCurrent()
        assertEquals(0, writesToDisk)
        assertNull(favorites.pending.value)
        assertFalse(favorites.error.value)
    }

    @Test fun `truncated day details cannot claim complete favorites`() = runTest {
        val api = object : FavoritesApi {
            override suspend fun days(favorite: Int) = listOf(MemoriesDayDto(1, 2))
            override suspend fun photos(ids: String, favorite: Int) = listOf(MemoriesPhotoDto(1, 1))
        }
        try { loadFavoritePhotos(api); fail("Truncated collection accepted") } catch (_: IOException) { }
    }
}

package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.ui.pluralText
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AlbumAdditionTest {
    @Test fun `request keeps server copies and counts local-only objects`() {
        val request = AlbumAdditionRequest.of(
            listOf(
                item("remote", MediaAssetRef.MemoriesFile(10)),
                item("merged", MediaAssetRef.LocalFirst(local("merged"), MediaAssetRef.MemoriesFile(11))),
                item("local", local("local")),
            ),
        )
        assertEquals(listOf(10L, 11L), request.fileIds)
        assertEquals(1, request.skippedLocal)
    }

    @Test fun `only albums owned by current user are writable`() {
        val albums = listOf(
            album("own", "anna/Лето"),
            album("shared", "boris/Лето"),
            album("folder", null),
        )
        assertEquals(listOf("own"), writableAlbums(albums, "anna").map { it.id })
    }

    @Test fun `new album name rejects blanks, slashes and existing names`() {
        assertEquals(AlbumNameProblem.Blank, albumNameProblem("  ", emptySet()))
        assertEquals(AlbumNameProblem.Slash, albumNameProblem("a/b", emptySet()))
        assertEquals(AlbumNameProblem.Exists, albumNameProblem(" Лето ", setOf("Лето")))
        assertNull(albumNameProblem("Лето & море", setOf("Лето")))
    }

    @Test fun `result message mentions only non-zero counters`() {
        assertEquals(
            listOf(
                uiText(R.string.album_add_done, "Лето", 3),
                pluralText(R.plurals.album_add_done_failed, 1, 1),
                uiText(R.string.album_add_done_skipped, 2),
            ),
            albumAdditionMessage(
                AlbumAdditionState.Done("Лето", AlbumAddResult(3, 0, 1), skippedLocal = 2),
            ),
        )
        assertNull(albumAdditionMessage(AlbumAdditionState.Idle))
    }

    @Test fun `additions create album, add files and refresh catalog`() = runBlocking {
        MemoriesAlbumWriterTest.Fixture { request ->
            when {
                request.method == "MKCOL" -> 201 to ""
                request.target.startsWith("/apps/memories/api/image/info/") ->
                    200 to """{"filename":"/Photos/a.jpg","basename":"a.jpg"}"""
                request.method == "COPY" -> 201 to ""
                else -> 404 to ""
            }
        }.use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val sessions = SessionStore(FixedCredentials(fixture.credentials))
                var refreshed = 0
                val catalog = AlbumCatalogRepository(
                    sessions,
                    object : RemoteAlbumSource {
                        override suspend fun load(credentials: AccountCredentials): RemoteAlbums {
                            refreshed++
                            return RemoteAlbums(emptyList())
                        }
                    },
                    object : LocalAlbumSource {
                        override suspend fun load() = emptyList<AlbumSummary>()
                        override fun changes() = emptyFlow<Unit>()
                    },
                    MutableStateFlow<LocalMediaPermissionMode?>(null),
                    scope,
                )
                val additions = AlbumAdditions(
                    sessions,
                    MemoriesAlbumWriter(fixture.writerTransport),
                    catalog,
                    scope,
                )
                val before = refreshed
                assertTrue(additions.add(AlbumTarget.New("Поход"), AlbumAdditionRequest(listOf(1L), 1)))
                val done = withTimeout(5_000) {
                    additions.state.first { it is AlbumAdditionState.Done }
                } as AlbumAdditionState.Done
                assertEquals(AlbumAddResult(1, 0, 0), done.result)
                assertEquals(1, done.skippedLocal)
                assertTrue(fixture.requests.any { it.method == "MKCOL" && it.decodedTarget.endsWith("/photos/anna/albums/Поход") })
                withTimeout(5_000) { while (refreshed == before) kotlinx.coroutines.delay(10) }
                additions.consume()
                assertEquals(AlbumAdditionState.Idle, additions.state.value)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun `second addition is rejected while the first is running`() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        MemoriesAlbumWriterTest.Fixture { request ->
            when {
                request.target.startsWith("/apps/memories/api/image/info/") ->
                    200 to """{"filename":"/Photos/a.jpg","basename":"a.jpg"}"""
                request.method == "COPY" -> {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    201 to ""
                }
                else -> 404 to ""
            }
        }.use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val sessions = SessionStore(FixedCredentials(fixture.credentials))
                val additions = AlbumAdditions(
                    sessions,
                    MemoriesAlbumWriter(fixture.writerTransport),
                    emptyCatalog(sessions, scope),
                    scope,
                )
                val album = AlbumTarget.Existing(AlbumLocation.Remote("anna/Лето"), "Лето")
                val request = AlbumAdditionRequest(listOf(1L), 0)
                assertTrue(additions.add(album, request))
                assertFalse(additions.add(album, request))
                release.countDown()
                withTimeout(5_000) { additions.state.first { it is AlbumAdditionState.Done } }
                additions.consume()
                withTimeout(5_000) {
                    while (!additions.add(album, request)) kotlinx.coroutines.delay(10)
                }
            } finally {
                release.countDown()
                scope.cancel()
            }
        }
    }

    private fun emptyCatalog(sessions: SessionStore, scope: CoroutineScope) =
        AlbumCatalogRepository(
            sessions,
            object : RemoteAlbumSource {
                override suspend fun load(credentials: AccountCredentials) = RemoteAlbums(emptyList())
            },
            object : LocalAlbumSource {
                override suspend fun load() = emptyList<AlbumSummary>()
                override fun changes() = emptyFlow<Unit>()
            },
            MutableStateFlow<LocalMediaPermissionMode?>(null),
            scope,
        )

    private class FixedCredentials(private val credentials: AccountCredentials) : CredentialsStore {
        override fun load() = credentials
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    }

    private fun local(id: String) = MediaAssetRef.LocalContent("content://media/$id", 1)

    private fun item(id: String, asset: MediaAssetRef) =
        MediaItem(
            mediaId = MediaId(id),
            dayId = 1,
            displayName = "$id.jpg",
            mimeType = "image/jpeg",
            width = null,
            height = null,
            etag = null,
            livePhotoId = null,
            auid = null,
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = 1,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = asset,
        )

    private fun album(id: String, clusterId: String?) =
        AlbumSummary(
            id = id,
            name = id,
            origin = if (clusterId == null) AlbumOrigin.Phone else AlbumOrigin.Nextcloud,
            count = 1,
            detail = "",
            location = clusterId?.let(AlbumLocation::Remote),
        )
}

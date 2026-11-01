package com.syrok0010.nextgallery.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.app.library.TimelineProjectionStore
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import com.syrok0010.nextgallery.feature.albums.AlbumAdditions
import com.syrok0010.nextgallery.feature.albums.AlbumCatalogRepository
import com.syrok0010.nextgallery.feature.albums.AlbumRemovals
import com.syrok0010.nextgallery.feature.deletion.MediaDeletionRepository
import com.syrok0010.nextgallery.feature.favorites.CloudFavorites
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import com.syrok0010.nextgallery.feature.library.CloudFavoriteMetadata
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesPhotoDto
import com.syrok0010.nextgallery.feature.timeline.remote.toMediaItem
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.context.GlobalContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@RunWith(AndroidJUnit4::class)
class FavoriteDeletionTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun closeScope() {
        scope.cancel()
    }

    @Test fun filterSelectionPreparesDeletionForPhotoOutsideHydratedTimeline() {
        showFavoriteOutsideTimeline()
        rule.onNodeWithContentDescription("old-favorite.jpg").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Удалить").performClick()
        rule.onNodeWithText("Только в облаке: 1").assertExists()
        rule.onNodeWithText("Отмена").performClick()
        rule.onNodeWithText("Только в облаке: 1").assertDoesNotExist()
    }

    @Test fun filterViewerPreparesDeletionForPhotoOutsideHydratedTimeline() {
        showFavoriteOutsideTimeline()
        rule.onNodeWithContentDescription("old-favorite.jpg").performClick()
        rule.onNodeWithContentDescription("Удалить").performClick()
        rule.onNodeWithText("Только в облаке: 1").assertExists()
        rule.onNodeWithText("Отмена").performClick()
    }

    private fun showFavoriteOutsideTimeline() {
        val koin = GlobalContext.get()
        // Only the disposable automation process is used. Its real index stays empty;
        // the server-filtered collection supplies an otherwise unhydrated object.
        koin.get<SessionStore>().signOut()
        val credentials = AccountCredentials("http://127.0.0.1:1", "fixture", "fixture", "fixture")
        val sessions = SessionStore(object : CredentialsStore {
            override fun load() = credentials
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        })
        val item = MemoriesPhotoDto(9999, 10957, epoch = 946684800, basename = "old-favorite.jpg")
            .toMediaItem(MediaId("unhydrated-favorite"))
            .copy(isFavorite = true)
        val favorites = CloudFavorites(
            sessions,
            CloudFavoriteMetadata(sessions),
            { listOf(item) },
            MediaLibraryIndex(),
            scope,
            write = { _, _, _ -> error("Unexpected DAV") },
            persist = {
                error("Unexpected write")
            },
        )
        val screenKoin = koinApplication {
            modules(
                module {
                    single<TimelineProjectionStore> { koin.get() }
                    single<MediaLibraryCoordinator> { koin.get() }
                    single<AlbumAdditions> { koin.get() }
                    single<AlbumRemovals> { koin.get() }
                    single<AlbumCatalogRepository> { koin.get() }
                    single<UploadQueue> { koin.get() }
                    single<MediaDeletionRepository> { koin.get() }
                    single<VideoPlayerFactory> { koin.get() }
                    single { favorites }
                    single {
                        MediaImageRequestFactory(
                            InstrumentationRegistry.getInstrumentation().targetContext,
                            sessions,
                        )
                    }
                },
            )
        }
        rule.setContent {
            KoinIsolatedContext(screenKoin) {
                NextGalleryTheme { PhotosScreen({}, {}) }
            }
        }
        rule.onNodeWithText("Избранное в облаке").performClick()
        rule.waitUntil(5000) { favorites.state.value.loaded }
        rule.runOnIdle { assertTrue(koin.get<MediaLibraryIndex>().state.value.isEmpty()) }
        rule.onNodeWithContentDescription("old-favorite.jpg").assertExists()
    }
}

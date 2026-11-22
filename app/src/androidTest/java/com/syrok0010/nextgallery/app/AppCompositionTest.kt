package com.syrok0010.nextgallery.app

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import coil3.ImageLoader
import com.syrok0010.nextgallery.app.ui.SessionViewModel
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.AuthenticatedSession
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.di.AuthenticatedGraph
import com.syrok0010.nextgallery.di.ProductionGraph
import com.syrok0010.nextgallery.feature.albums.AlbumAdditions
import com.syrok0010.nextgallery.feature.albums.AlbumContentsViewModel
import com.syrok0010.nextgallery.feature.albums.AlbumLocation
import com.syrok0010.nextgallery.feature.albums.AlbumsViewModel
import com.syrok0010.nextgallery.feature.auth.LoginViewModel
import com.syrok0010.nextgallery.feature.deletion.MediaDeletionRepository
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import com.syrok0010.nextgallery.feature.images.ThumbnailBatchLoader
import com.syrok0010.nextgallery.feature.upload.UploadJournal
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import com.syrok0010.nextgallery.feature.upload.UploadWorker
import com.syrok0010.nextgallery.feature.viewer.VideoFramesFactory
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named

@RunWith(AndroidJUnit4::class)
class AppCompositionTest {
    private val account = AccountCredentials("http://127.0.0.1:9", "fixture", "password", "fixture")

    @Test fun bootstrapDoesNotExposeAuthenticatedServices() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ProductionGraph().use { graph ->
                val store = ViewModelStore()
                try {
                    store.put("auth", graph.koin.get<LoginViewModel>())
                    store.put("session", graph.koin.get<SessionViewModel>())
                    assertNull(graph.koin.getOrNull<UploadQueue>())
                    assertNull(graph.koin.getOrNull<UploadJournal>())
                    assertNull(graph.koin.getOrNull<ImageLoader>())
                    assertThrows(IllegalStateException::class.java) {
                        graph.koin.get<AuthenticatedGraph>().requireScope()
                    }
                } finally {
                    store.clear()
                }
            }
        }
    }

    @Test fun loginStartsOneAuthenticatedGraphWithAllEntryPoints() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ProductionGraph().use { graph ->
                val store = ViewModelStore()
                try {
                    graph.koin.get<SessionStore>().signIn(account)
                    val scope = graph.scope
                    assertSame(
                        scope,
                        graph.koin
                            .get<AuthenticatedGraph>()
                            .also { it.start(account) }
                            .requireScope(),
                    )
                    assertEquals(account, scope.get<AuthenticatedSession>().credentials)

                    val queue = scope.get<UploadQueue>()
                    val sources = scope.getAll<OperationStatusSource>()
                    assertEquals(3, sources.size)
                    assertTrue(sources.any { it === queue })
                    assertTrue(sources.any { it === scope.get<AlbumAdditions>() })
                    assertTrue(sources.any { it === scope.get<MediaDeletionRepository>() })

                    assertNull(graph.koin.getOrNull<UploadJournal>())
                    assertNotNull(scope.get<UploadJournal>())
                    store.put("albums", scope.get<AlbumsViewModel>())
                    store.put(
                        "album",
                        scope.get<AlbumContentsViewModel> {
                            parametersOf(AlbumLocation.Remote("a"))
                        },
                    )
                    assertNotNull(scope.get<ThumbnailBatchLoader>())
                    assertNotNull(scope.get<MediaImageRequestFactory>())
                    assertNotNull(scope.get<ImageLoader>())
                    assertNotNull(scope.get<VideoPlayerFactory>())
                    assertNotNull(scope.get<VideoFramesFactory>())
                } finally {
                    store.clear()
                }
            }
        }
    }

    @Test fun workerDefinitionSharesAuthenticatedQueueWithoutOpeningUi() {
        ProductionGraph(account).use { graph ->
            val worker = buildUploadWorker(graph)
            assertSame(graph.scope.get<UploadQueue>(), worker.queue)
            assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        }
    }

    @Test fun workerDefinitionSucceedsWithoutCrashingWhenSignedOut() {
        ProductionGraph(credentials = null).use { graph ->
            val worker = buildUploadWorker(graph)
            assertNull(worker.queue)
            assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        }
    }

    @Test fun savedCredentialsStartGraphWithoutAnActivityAndCloseCancelsItsWork() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val graph = ProductionGraph(account)
            val scope = graph.scope
            val mainWork = scope.get<CoroutineScope>()
            val thumbnailWork = scope.get<CoroutineScope>(named<ThumbnailBatchLoader>())
            assertTrue(mainWork.isActive)
            assertTrue(thumbnailWork.isActive)
            graph.close()
            assertFalse(mainWork.isActive)
            assertFalse(thumbnailWork.isActive)
            assertTrue(scope.closed)
        }
    }

    private fun buildUploadWorker(graph: ProductionGraph): UploadWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                context: Context,
                name: String,
                params: WorkerParameters,
            ): ListenableWorker? =
                graph.koin.getOrNull(qualifier = named(name)) { parametersOf(params) }
        }
        return TestListenableWorkerBuilder<UploadWorker>(graph.context)
            .setWorkerFactory(factory)
            .build()
    }
}

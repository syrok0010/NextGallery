package com.syrok0010.nextgallery.app

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.app.ui.SessionViewModel
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.di.AuthenticatedGraph
import com.syrok0010.nextgallery.di.ProductionGraph
import com.syrok0010.nextgallery.feature.albums.*
import com.syrok0010.nextgallery.feature.auth.LoginViewModel
import com.syrok0010.nextgallery.feature.deletion.MediaDeletionRepository
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import com.syrok0010.nextgallery.feature.upload.UploadJournal
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.parameter.parametersOf

@RunWith(AndroidJUnit4::class)
class AppCompositionTest {
    private val account = AccountCredentials("http://127.0.0.1:9", "fixture", "password", "fixture")

    @Test fun bootstrapDoesNotExposeAuthenticatedServicesAndLoginStartsOneGraph() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ProductionGraph().use { graph ->
                val store = ViewModelStore()
                try {
                    store.put("auth", graph.koin.get<LoginViewModel>())
                    store.put("session", graph.koin.get<SessionViewModel>())
                    assertNull(graph.koin.getOrNull<UploadQueue>())
                    assertNull(graph.koin.getOrNull<coil3.ImageLoader>())
                    assertThrows(IllegalStateException::class.java) {
                        graph.koin.get<AuthenticatedGraph>().requireScope()
                    }
                    graph.koin.get<SessionStore>().signIn(account)
                    val scope = graph.scope
                    assertSame(
                        scope,
                        graph.koin
                            .get<AuthenticatedGraph>()
                            .also {
                                it.start(
                                    account,
                                )
                            }.requireScope(),
                    )
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
                            parametersOf(
                                AlbumLocation.Remote("a"),
                            )
                        },
                    )
                    scope.get<MediaImageRequestFactory>()
                } finally {
                    store.clear()
                }
            }
        }
    }

    @Test fun workerDefinitionRunsWithStoredCredentialsWithoutOpeningUi() {
        ProductionGraph(account).use { graph ->
            val factory = object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    context: android.content.Context,
                    name: String,
                    params: androidx.work.WorkerParameters,
                ): androidx.work.ListenableWorker? =
                    graph.koin.getOrNull(
                        qualifier = org.koin.core.qualifier.named(name),
                    ) { parametersOf(params) }
            }
            val worker = androidx.work.testing
                .TestListenableWorkerBuilder<com.syrok0010.nextgallery.feature.upload.UploadWorker>(
                    graph.context,
                ).setWorkerFactory(factory)
                .build()
            assertEquals(
                androidx.work.ListenableWorker.Result.success(),
                kotlinx.coroutines
                    .runBlocking {
                        worker.doWork()
                    },
            )
        }
    }

    @Test fun savedCredentialsStartGraphWithoutAnActivityAndCloseCancelsItsWork() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val graph = ProductionGraph(account)
            val scope = graph.scope
            val work = scope.get<CoroutineScope>()
            assertTrue(work.isActive)
            assertSame(
                scope.get<UploadQueue>(),
                graph.koin.get<AuthenticatedGraph>().requireScope().get<UploadQueue>(),
            )
            graph.close()
            assertFalse(work.isActive)
            assertTrue(scope.closed)
        }
    }
}

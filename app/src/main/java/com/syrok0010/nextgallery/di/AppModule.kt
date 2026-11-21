package com.syrok0010.nextgallery.di

import android.content.Context
import com.syrok0010.nextgallery.app.ui.AppDataEraser
import com.syrok0010.nextgallery.app.ui.SessionViewModel
import com.syrok0010.nextgallery.app.ui.SystemAppDataEraser
import com.syrok0010.nextgallery.app.ui.navigationModule
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.auth.NextcloudAuthClient
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.KeystoreCredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.auth.LoginGateway
import com.syrok0010.nextgallery.feature.auth.LoginViewModel
import com.syrok0010.nextgallery.feature.auth.NextcloudLoginRepository
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import com.syrok0010.nextgallery.router.routerModule
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.viewModel as explicitViewModel
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.plugin.module.dsl.*

private fun bootstrapModule() =
    module {
        single {
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                explicitNulls = false
            }
        }
        single<NextcloudTransport>()
        single<NextcloudAuthClient>()
        single<NextcloudLoginRepository>() bind LoginGateway::class
        single<CredentialsStore> { create(::credentialsStore) }
        single { AuthenticatedGraph(getKoin()) } onClose { it?.close() }
        single { create(::sessionStore) }
        single<AppDataEraser> { create(::appDataEraser) }
        explicitViewModel { create(::sessionViewModel) }
        viewModel<LoginViewModel>()
    }

private fun credentialsStore(context: Context, json: Json): CredentialsStore =
    KeystoreCredentialsStore(context, json)
private fun appDataEraser(context: Context): AppDataEraser = SystemAppDataEraser(context)
private fun sessionStore(store: CredentialsStore, graph: AuthenticatedGraph) =
    SessionStore(store, graph::start)
private fun sessionViewModel(
    store: SessionStore,
    graph: AuthenticatedGraph,
    eraser: AppDataEraser,
) = SessionViewModel(store) { graph.requireScope().get<UploadQueue>().logout(eraser::erase) }

fun appModule() =
    module {
        includes(
            bootstrapModule(),
            storageModule(),
            authenticatedModule(),
            workerModule(),
            navigationModule(),
            routerModule(),
        )
    }

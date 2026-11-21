package com.syrok0010.nextgallery.di

import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.upload.UploadWorker
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.dsl.module
import org.koin.plugin.module.dsl.*

internal fun workerModule() =
    module {
        worker { params ->
            // A background cold start may request the worker before Application finishes eager setup.
            get<SessionStore>()
            val graph = get<AuthenticatedGraph>().requireScope()
            UploadWorker(androidContext(), params.get(), graph.get(), graph.get(), graph.get())
        }
    }

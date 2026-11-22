package com.syrok0010.nextgallery.di

import com.syrok0010.nextgallery.feature.upload.UploadWorker
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.dsl.module

internal fun workerModule() =
    module {
        worker { params ->
            // A background cold start may request the worker before Application finishes eager setup.
            val scope = get<AuthenticatedGraph>().startIfSignedIn(get())
            UploadWorker(
                context = androidContext(),
                params = params.get(),
                queue = scope?.get(),
                transport = scope?.get(),
                memories = scope?.get(),
            )
        }
    }

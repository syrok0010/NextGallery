package com.syrok0010.nextgallery

import android.app.Application
import com.syrok0010.nextgallery.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class NextGalleryApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@NextGalleryApplication)
            modules(appModule)
        }
    }
}

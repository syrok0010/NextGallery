package com.syrok0010.nextgallery

import android.app.Application
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class NextGalleryApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val koin = startKoin {
            androidContext(this@NextGalleryApplication)
            if (BuildConfig.DEBUG) androidLogger(Level.DEBUG)
            allowOverride(false)
            modules(appModule())
            workManagerFactory()
        }.koin

        koin.get<SessionStore>()
        coil3.SingletonImageLoader.setSafe { koin.get<coil3.ImageLoader>() }
    }
}

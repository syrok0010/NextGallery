package com.syrok0010.nextgallery

import android.app.Application
import coil3.SingletonImageLoader
import com.syrok0010.nextgallery.di.appModule
import com.syrok0010.nextgallery.feature.images.ThumbnailBatchLoader
import com.syrok0010.nextgallery.feature.images.ThumbnailFileStore
import com.syrok0010.nextgallery.feature.images.createNextGalleryImageLoader
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class NextGalleryApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val koin = startKoin {
            androidContext(this@NextGalleryApplication)
            modules(appModule)
        }.koin

        SingletonImageLoader.setSafe { context ->
            createNextGalleryImageLoader(
                context = context,
                thumbnailBatchLoader = koin.get<ThumbnailBatchLoader>(),
                thumbnailFileStore = koin.get<ThumbnailFileStore>(),
            )
        }
    }
}

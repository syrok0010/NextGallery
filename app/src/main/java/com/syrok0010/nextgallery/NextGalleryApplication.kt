package com.syrok0010.nextgallery

import android.app.Application
import coil3.SingletonImageLoader
import com.syrok0010.nextgallery.data.cache.ThumbnailFileStore
import com.syrok0010.nextgallery.data.thumbnail.ThumbnailBatchLoader
import com.syrok0010.nextgallery.data.thumbnail.createNextGalleryImageLoader
import com.syrok0010.nextgallery.di.appModule
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

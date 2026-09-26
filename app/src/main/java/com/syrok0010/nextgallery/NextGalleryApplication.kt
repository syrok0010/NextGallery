package com.syrok0010.nextgallery

import android.app.Application
import coil3.SingletonImageLoader
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.di.appModule
import com.syrok0010.nextgallery.feature.images.ThumbnailBatchLoader
import com.syrok0010.nextgallery.feature.images.ThumbnailFileStore
import com.syrok0010.nextgallery.feature.images.createNextGalleryImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named

class NextGalleryApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val koin = startKoin {
            androidContext(this@NextGalleryApplication)
            modules(appModule)
        }.koin

        val libraryScope = koin.get<CoroutineScope>(named("libraryScope"))
        val coordinator = koin.get<MediaLibraryCoordinator>()
        libraryScope.launch { coordinator.run() }

        SingletonImageLoader.setSafe { context ->
            createNextGalleryImageLoader(
                context = context,
                thumbnailBatchLoader = koin.get<ThumbnailBatchLoader>(),
                thumbnailFileStore = koin.get<ThumbnailFileStore>(),
            )
        }
    }
}

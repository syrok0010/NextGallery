package com.syrok0010.nextgallery

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import coil3.SingletonImageLoader
import com.syrok0010.nextgallery.app.library.LibraryPublicationStore
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.core.locale.initializeAppLanguage
import com.syrok0010.nextgallery.core.locale.withAppLanguage
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
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.withAppLanguage())
    }

    @Suppress("DEPRECATION")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep service/notification resources Russian after a system language change too.
        resources.updateConfiguration(newConfig.withAppLanguage(), resources.displayMetrics)
    }

    override fun onCreate() {
        super.onCreate()
        initializeAppLanguage()

        val koin = startKoin {
            androidContext(this@NextGalleryApplication)
            modules(appModule)
        }.koin

        val libraryScope = koin.get<CoroutineScope>(named("libraryScope"))
        val coordinator = koin.get<MediaLibraryCoordinator>()
        val publication = koin.get<LibraryPublicationStore>()
        libraryScope.launch { coordinator.run(publication::publish) }

        SingletonImageLoader.setSafe { context ->
            createNextGalleryImageLoader(
                context = context,
                thumbnailBatchLoader = koin.get<ThumbnailBatchLoader>(),
                thumbnailFileStore = koin.get<ThumbnailFileStore>(),
            )
        }
    }
}

package com.syrok0010.nextgallery.di

import com.syrok0010.nextgallery.app.ui.SessionViewModel
import com.syrok0010.nextgallery.core.database.NextGalleryDatabase
import com.syrok0010.nextgallery.core.database.RoomMediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.KeystoreCredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.auth.LoginViewModel
import com.syrok0010.nextgallery.feature.auth.NextcloudLoginRepository
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import com.syrok0010.nextgallery.feature.images.MemoriesMultipreviewClient
import com.syrok0010.nextgallery.feature.images.RemoteImageCache
import com.syrok0010.nextgallery.feature.images.RemoteImageRepository
import com.syrok0010.nextgallery.feature.images.ThumbnailBatchLoader
import com.syrok0010.nextgallery.feature.images.ThumbnailFileStore
import com.syrok0010.nextgallery.feature.timeline.AuthenticatedViewModel
import com.syrok0010.nextgallery.feature.timeline.UnifiedTimelineProjection
import com.syrok0010.nextgallery.feature.timeline.local.AndroidMediaStoreChangeObserver
import com.syrok0010.nextgallery.feature.timeline.local.AndroidMediaStoreReader
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionCoordinator
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaProjectionRepository
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaProjectionStore
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaSource
import com.syrok0010.nextgallery.feature.timeline.persistence.TimelineCacheRepository
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }

    single { NextcloudTransport(get()) }
    single<CredentialsStore> { KeystoreCredentialsStore(androidContext(), get()) }
    single { NextGalleryDatabase.create(androidContext()) }
    single { ThumbnailFileStore(androidContext()) }
    single { RoomMediaIdentityRegistry(get()) }
    single<MediaIdentityRegistry> { get<RoomMediaIdentityRegistry>() }
    single { TimelineCacheRepository(get(), get(), get()) }
    factory { UnifiedTimelineProjection() }
    single { LocalMediaPermissionCoordinator(androidContext()) }
    single {
        val context = androidContext()
        val permissions = get<LocalMediaPermissionCoordinator>()
        AndroidMediaStoreReader(
            context.contentResolver,
            get<NextGalleryDatabase>().localMediaMetadataDao(),
            volumeVersions = {
                check(permissions.currentMode() == LocalMediaPermissionMode.Full) { "Full media permission required" }
                android.provider.MediaStore.getExternalVolumeNames(context).associateWith { volume ->
                    checkNotNull(android.provider.MediaStore.getVersion(context, volume))
                }.also { check(it.isNotEmpty()) { "No mounted media volumes" } }
            },
        )
    }
    single { AndroidMediaStoreChangeObserver(androidContext().contentResolver) }
    single<LocalMediaProjectionStore> { LocalMediaProjectionRepository(get()) }
    single {
        LocalMediaSource(
            reader = get<AndroidMediaStoreReader>(),
            projectionStore = get<LocalMediaProjectionStore>(),
            identityRegistry = get<MediaIdentityRegistry>(),
            changeObserver = get<AndroidMediaStoreChangeObserver>(),
        )
    }
    single { NextcloudLoginRepository(get()) }
    single { MemoriesMultipreviewClient(get(), get()) }
    single { MemoriesRepository(get(), get(), get()) }
    single { RemoteImageCache(get(), get()) }
    single { RemoteImageRepository(get(), get()) }
    single {
        val images = get<RemoteImageRepository>()
        ThumbnailBatchLoader(
            loadBatch = { credentials, keys -> images.ensureThumbnails(credentials, keys).toSet() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
    single { SessionStore(get()) }
    single { VideoPlayerFactory(get(), get()) }
    single { com.syrok0010.nextgallery.feature.viewer.VideoFramesFactory(androidContext(), get()) }
    single { MediaImageRequestFactory(androidContext(), get()) }

    viewModelOf(::SessionViewModel)
    viewModel { LoginViewModel(get(), get(), get<NextcloudLoginRepository>()) }
    viewModelOf(::AuthenticatedViewModel)
}

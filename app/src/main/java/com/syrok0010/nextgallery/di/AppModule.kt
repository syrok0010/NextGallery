package com.syrok0010.nextgallery.di

import com.syrok0010.nextgallery.data.auth.NextcloudLoginRepository
import com.syrok0010.nextgallery.data.cache.ThumbnailFileStore
import com.syrok0010.nextgallery.data.cache.NextGalleryDatabase
import com.syrok0010.nextgallery.data.cache.TimelineCacheRepository
import com.syrok0010.nextgallery.data.cache.RoomMediaIdentityRegistry
import com.syrok0010.nextgallery.data.credentials.CredentialsStore
import com.syrok0010.nextgallery.data.credentials.KeystoreCredentialsStore
import com.syrok0010.nextgallery.data.memories.MemoriesMultipreviewClient
import com.syrok0010.nextgallery.data.memories.MemoriesRepository
import com.syrok0010.nextgallery.data.memories.MediaIdentityRegistry
import com.syrok0010.nextgallery.data.memories.UnifiedTimelineProjection
import com.syrok0010.nextgallery.data.local.AndroidMediaStoreChangeObserver
import com.syrok0010.nextgallery.data.local.AndroidMediaStoreReader
import com.syrok0010.nextgallery.data.local.LocalMediaPermissionCoordinator
import com.syrok0010.nextgallery.data.local.LocalMediaProjectionStore
import com.syrok0010.nextgallery.data.local.LocalMediaProjectionRepository
import com.syrok0010.nextgallery.data.local.LocalMediaSource
import com.syrok0010.nextgallery.data.network.NextcloudTransport
import com.syrok0010.nextgallery.data.thumbnail.ThumbnailBatchLoader
import com.syrok0010.nextgallery.ui.SessionStore
import com.syrok0010.nextgallery.ui.SessionViewModel
import com.syrok0010.nextgallery.ui.auth.LoginViewModel
import com.syrok0010.nextgallery.ui.common.MediaImageRequestFactory
import com.syrok0010.nextgallery.ui.timeline.AuthenticatedViewModel
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
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
    single { AndroidMediaStoreReader(androidContext().contentResolver) }
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
    single { MemoriesRepository(get(), get(), get(), get()) }
    single { ThumbnailBatchLoader(get()) }
    single { SessionStore(get()) }
    single { MediaImageRequestFactory(androidContext(), get()) }

    viewModelOf(::SessionViewModel)
    viewModelOf(::LoginViewModel)
    viewModelOf(::AuthenticatedViewModel)
}

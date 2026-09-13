package com.syrok0010.nextgallery.di

import android.provider.MediaStore
import com.syrok0010.nextgallery.app.ui.SessionViewModel
import com.syrok0010.nextgallery.core.database.NextGalleryDatabase
import com.syrok0010.nextgallery.core.database.RoomMediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.KeystoreCredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.albums.AlbumContentsSource
import com.syrok0010.nextgallery.feature.albums.AlbumContentsViewModel
import com.syrok0010.nextgallery.feature.albums.AlbumLocation
import com.syrok0010.nextgallery.feature.albums.AndroidAlbumContents
import com.syrok0010.nextgallery.feature.albums.MemoriesAlbumContents
import com.syrok0010.nextgallery.feature.albums.AlbumsViewModel
import com.syrok0010.nextgallery.feature.albums.AndroidAlbumSource
import com.syrok0010.nextgallery.feature.albums.LocalAlbumSource
import com.syrok0010.nextgallery.feature.albums.MemoriesAlbumSource
import com.syrok0010.nextgallery.feature.albums.RemoteAlbumSource
import com.syrok0010.nextgallery.feature.auth.LoginViewModel
import com.syrok0010.nextgallery.feature.auth.NextcloudLoginRepository
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import com.syrok0010.nextgallery.feature.images.MemoriesMultipreviewClient
import com.syrok0010.nextgallery.feature.images.RemoteImageCache
import com.syrok0010.nextgallery.feature.images.RemoteImageRepository
import com.syrok0010.nextgallery.feature.images.ThumbnailBatchLoader
import com.syrok0010.nextgallery.feature.images.ThumbnailFileStore
import com.syrok0010.nextgallery.feature.timeline.TimelineViewModel
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
import com.syrok0010.nextgallery.feature.viewer.VideoFramesFactory
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.koin.core.qualifier.named
import com.syrok0010.nextgallery.feature.timeline.TimelineRepository
import com.syrok0010.nextgallery.feature.albums.AlbumCatalogRepository

val appModule = module {
    single<RemoteAlbumSource> { MemoriesAlbumSource(get()) }
    single<LocalAlbumSource> { AndroidAlbumSource(androidContext().contentResolver) }
    single(named("libraryScope")) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    single { AlbumCatalogRepository(get(), get(), get(), get<LocalMediaPermissionCoordinator>().mode, get(named("libraryScope"))) }
    single { TimelineRepository(get(), get<MemoriesRepository>(), get(), get<LocalMediaPermissionCoordinator>().mode, get(named("libraryScope"))) }
    viewModel { AlbumsViewModel(get()) }
    single { MemoriesAlbumContents(get(), get()) }
    single { AndroidAlbumContents(androidContext().contentResolver, get(), get()) }
    single<AlbumContentsSource> {
        val remote = get<MemoriesAlbumContents>()
        val local = get<AndroidAlbumContents>()
        AlbumContentsSource { location, credentials ->
            when (location) {
                is AlbumLocation.Remote -> remote.load(location, credentials)
                is AlbumLocation.Folder -> local.load(location)
            }
        }
    }
    viewModel { AlbumContentsViewModel(get(), get(), get<TimelineRepository>().state, get<LocalMediaPermissionCoordinator>().mode) }


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
                MediaStore.getExternalVolumeNames(context).associateWith { volume ->
                    checkNotNull(MediaStore.getVersion(context, volume))
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
    single { VideoFramesFactory(androidContext(), get()) }
    single { MediaImageRequestFactory(androidContext(), get()) }

    viewModelOf(::SessionViewModel)
    viewModel { LoginViewModel(get(), get(), get<NextcloudLoginRepository>()) }
    viewModelOf(::TimelineViewModel)
}

package com.syrok0010.nextgallery.di

import android.provider.MediaStore
import com.syrok0010.nextgallery.app.library.LibraryPublicationStore
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.app.library.TimelineProjectionStore
import com.syrok0010.nextgallery.app.ui.AppDataEraser
import com.syrok0010.nextgallery.app.ui.SessionViewModel
import com.syrok0010.nextgallery.app.ui.SystemAppDataEraser
import com.syrok0010.nextgallery.core.database.NextGalleryDatabase
import com.syrok0010.nextgallery.core.database.RoomMediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.auth.NextcloudAuthClient
import com.syrok0010.nextgallery.core.network.capabilities.NextcloudCapabilitiesClient
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.network.dav.PhotosAlbumsDavClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.KeystoreCredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.albums.AlbumAdditions
import com.syrok0010.nextgallery.feature.albums.AlbumCatalogRepository
import com.syrok0010.nextgallery.feature.albums.AlbumContentsSource
import com.syrok0010.nextgallery.feature.albums.AlbumContentsViewModel
import com.syrok0010.nextgallery.feature.albums.AlbumLocation
import com.syrok0010.nextgallery.feature.albums.AlbumUploadFollowUp
import com.syrok0010.nextgallery.feature.albums.AlbumsViewModel
import com.syrok0010.nextgallery.feature.albums.AndroidAlbumContents
import com.syrok0010.nextgallery.feature.albums.AndroidAlbumSource
import com.syrok0010.nextgallery.feature.albums.LocalAlbumSource
import com.syrok0010.nextgallery.feature.albums.MemoriesAlbumContents
import com.syrok0010.nextgallery.feature.albums.MemoriesAlbumSource
import com.syrok0010.nextgallery.feature.albums.MemoriesAlbumWriter
import com.syrok0010.nextgallery.feature.albums.RemoteAlbumSource
import com.syrok0010.nextgallery.feature.auth.LoginViewModel
import com.syrok0010.nextgallery.feature.auth.NextcloudLoginRepository
import com.syrok0010.nextgallery.feature.deletion.AndroidPhoneTrash
import com.syrok0010.nextgallery.feature.deletion.CloudTrash
import com.syrok0010.nextgallery.feature.deletion.MediaDeletionRepository
import com.syrok0010.nextgallery.feature.deletion.MemoriesCloudTrash
import com.syrok0010.nextgallery.feature.deletion.NextcloudTrashBin
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import com.syrok0010.nextgallery.feature.images.RemoteImageCache
import com.syrok0010.nextgallery.feature.images.RemoteImageRepository
import com.syrok0010.nextgallery.feature.images.ThumbnailBatchLoader
import com.syrok0010.nextgallery.feature.images.ThumbnailFileStore
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.AndroidMediaStoreChangeObserver
import com.syrok0010.nextgallery.feature.library.local.AndroidMediaStoreReader
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionCoordinator
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.library.local.LocalMediaProjectionRepository
import com.syrok0010.nextgallery.feature.library.local.LocalMediaProjectionStore
import com.syrok0010.nextgallery.feature.library.local.LocalMediaSource
import com.syrok0010.nextgallery.feature.timeline.persistence.TimelineCacheRepository
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesFiles
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesRepository
import com.syrok0010.nextgallery.feature.upload.UploadFollowUps
import com.syrok0010.nextgallery.feature.upload.UploadJournal
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import com.syrok0010.nextgallery.feature.upload.UploadQueueDatabase
import com.syrok0010.nextgallery.feature.upload.UploadSource
import com.syrok0010.nextgallery.feature.upload.UploadWorker
import com.syrok0010.nextgallery.feature.upload.UploadedCopiesFollowUp
import com.syrok0010.nextgallery.feature.viewer.VideoFramesFactory
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    single<RemoteAlbumSource> { MemoriesAlbumSource(get<MemoriesApiClient>()) }
    single<LocalAlbumSource> { AndroidAlbumSource(androidContext().contentResolver) }
    single(named("libraryScope")) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    single {
        AlbumCatalogRepository(
            get(),
            get(),
            get(),
            get<LocalMediaPermissionCoordinator>().mode,
            get(named("libraryScope")),
        )
    }
    single { MemoriesAlbumWriter(get<PhotosAlbumsDavClient>(), get<FilesDavClient>()) }
    single { MemoriesFiles(get<MemoriesApiClient>()) }
    single {
        val uploads: UploadQueue = get()
        AlbumAdditions(
            get(),
            get(),
            get(),
            { ids, album -> uploads.submit(ids, album) },
            get(named("libraryScope")),
        )
    } bind OperationStatusSource::class
    single<CloudTrash> { MemoriesCloudTrash(get(), get()) }
    single { NextcloudTrashBin(get()) }
    single {
        MediaDeletionRepository(
            get(),
            get(),
            get(),
            get(),
            AndroidPhoneTrash(androidContext().contentResolver),
            get(named("libraryScope")),
        ) {
            get<MediaLibraryCoordinator>().refresh()
        }
    } bind OperationStatusSource::class
    single { UploadQueueDatabase.open(androidContext()) }
    single { UploadJournal(get()) }
    single { UploadSource(androidContext().contentResolver) }
    single {
        val context = androidContext()
        UploadQueue(
            get(),
            get(),
            get(named("libraryScope")),
            get(),
            get<UploadSource>()::snapshot,
            UploadFollowUps(
                link = UploadedCopiesFollowUp(get()),
                album = AlbumUploadFollowUp(get(), get()),
            ),
            refreshLibrary = { get<MediaLibraryCoordinator>().refresh() },
            schedule = { UploadWorker.schedule(context) },
            restart = { UploadWorker.schedule(context, now = true) },
        )
    }
    single<OperationStatusSource> { get<UploadQueue>() }
    single { MediaLibraryIndex() }
    single { LibraryPublicationStore() }
    single { TimelineProjectionStore(get(), get(named("libraryScope"))) }
    single {
        MediaLibraryCoordinator(
            index = get(),
            sessions = get(),
            remoteSource = get<MemoriesRepository>(),
            localMedia = get(),
            permissions = get<LocalMediaPermissionCoordinator>().mode,
        )
    }
    viewModel { AlbumsViewModel(get()) }
    single { MemoriesAlbumContents(get<MemoriesApiClient>(), get()) }
    single { AndroidAlbumContents(androidContext().contentResolver, get(), get()) }
    single<AlbumContentsSource> {
        val remote = get<MemoriesAlbumContents>()
        val local = get<AndroidAlbumContents>()
        AlbumContentsSource { location, _ ->
            when (location) {
                is AlbumLocation.Remote -> remote.load(location)
                is AlbumLocation.Folder -> local.load(location)
            }
        }
    }
    viewModel { (location: AlbumLocation) ->
        AlbumContentsViewModel(
            get(),
            get(),
            get<MediaLibraryIndex>(),
            get<LocalMediaPermissionCoordinator>().mode,
            location,
        )
    }

    single {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }

    single { NextcloudTransport(get()) }
    single { NextcloudAuthClient(get()) }
    single { MemoriesApiClient(get(), get<SessionStore>()::requireCredentials) }
    single { PhotosAlbumsDavClient(get(), get<SessionStore>()::requireCredentials) }
    single { FilesDavClient(get(), get<SessionStore>()::requireCredentials) }
    single { NextcloudCapabilitiesClient(get(), get<SessionStore>()::requireCredentials) }
    single<CredentialsStore> { KeystoreCredentialsStore(androidContext(), get()) }
    single { NextGalleryDatabase.create(androidContext()) }
    single { ThumbnailFileStore(androidContext()) }
    single { RoomMediaIdentityRegistry(get()) }
    single<MediaIdentityRegistry> { get<RoomMediaIdentityRegistry>() }
    single { TimelineCacheRepository(get(), get(), get()) }
    single { LocalMediaPermissionCoordinator(androidContext()) }
    single {
        val context = androidContext()
        val permissions = get<LocalMediaPermissionCoordinator>()
        AndroidMediaStoreReader(
            context.contentResolver,
            get<NextGalleryDatabase>().localMediaMetadataDao(),
            volumeVersions = {
                check(permissions.currentMode() == LocalMediaPermissionMode.Full) {
                    "Full media permission required"
                }
                MediaStore
                    .getExternalVolumeNames(context)
                    .associateWith { volume ->
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
    single { NextcloudLoginRepository(get<NextcloudAuthClient>(), get<NextcloudTransport>()) }
    single { MemoriesRepository(get<MemoriesApiClient>(), get(), get()) }
    single { RemoteImageCache(get(), get()) }
    single { RemoteImageRepository(get(), get()) }
    single {
        val images = get<RemoteImageRepository>()
        ThumbnailBatchLoader(
            loadBatch = images::ensureThumbnails,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
    single { SessionStore(get()) }
    single { VideoPlayerFactory(get(), get()) }
    single { VideoFramesFactory(androidContext(), get()) }
    single { MediaImageRequestFactory(androidContext(), get()) }

    single<AppDataEraser> { SystemAppDataEraser(androidContext()) }
    viewModelOf(::SessionViewModel)
    viewModel { LoginViewModel(get(), get(), get<NextcloudLoginRepository>()) }
}

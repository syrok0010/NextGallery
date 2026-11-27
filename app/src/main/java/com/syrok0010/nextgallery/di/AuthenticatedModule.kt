package com.syrok0010.nextgallery.di

import android.content.Context
import android.provider.MediaStore
import com.syrok0010.nextgallery.app.library.LibraryPublicationStore
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.app.library.TimelineProjectionStore
import com.syrok0010.nextgallery.core.database.NextGalleryDatabase
import com.syrok0010.nextgallery.core.database.RoomMediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.capabilities.NextcloudCapabilitiesClient
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.network.dav.PhotosAlbumsDavClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.AuthenticatedSession
import com.syrok0010.nextgallery.feature.albums.AlbumContentsViewModel
import com.syrok0010.nextgallery.feature.albums.AlbumLocation
import com.syrok0010.nextgallery.feature.albums.AlbumUploadFollowUp
import com.syrok0010.nextgallery.feature.albums.Albums
import com.syrok0010.nextgallery.feature.albums.AlbumsViewModel
import com.syrok0010.nextgallery.feature.albums.AndroidFolders
import com.syrok0010.nextgallery.feature.albums.LocalFolders
import com.syrok0010.nextgallery.feature.albums.MemoriesAlbums
import com.syrok0010.nextgallery.feature.albums.RemoteAlbums
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
import com.syrok0010.nextgallery.feature.library.local.LocalMediaChangeObserver
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionCoordinator
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.library.local.LocalMediaProjectionRepository
import com.syrok0010.nextgallery.feature.library.local.LocalMediaProjectionStore
import com.syrok0010.nextgallery.feature.library.local.LocalMediaReader
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
import com.syrok0010.nextgallery.feature.viewer.ViewerDetailsLoader
import com.syrok0010.nextgallery.feature.viewer.ViewerMediaDetailsRepository
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.scoped

internal fun authenticatedModule() =
    module {
        scope<AuthenticatedSession> {
            scoped<AuthenticatedSession> { checkNotNull(getSource<AuthenticatedSession>()) }
            scoped<AccountCredentials> { get<AuthenticatedSession>().credentials }
            scoped { NextGalleryDatabase.create(get()) } onClose { it?.close() }
            scoped { UploadQueueDatabase.open(get()) } onClose { it?.close() }
            scoped<UploadJournal>()
            scoped<ThumbnailFileStore>()
            scoped<RoomMediaIdentityRegistry>() bind MediaIdentityRegistry::class
            scoped<TimelineCacheRepository>()
            scoped<RemoteImageCache>()
            scoped<LocalMediaProjectionRepository>() bind LocalMediaProjectionStore::class
            scoped {
                CoroutineScope(
                    SupervisorJob() + Dispatchers.Main.immediate,
                )
            } onClose { it?.cancel() }
            scoped { create(::memoriesClient) }
            scoped { create(::albumsClient) }
            scoped { create(::filesClient) }
            scoped<ViewerDetailsLoader> {
                ViewerMediaDetailsRepository(
                    get<Context>().contentResolver,
                    get<MemoriesApiClient>(),
                )
            }
            scoped { create(::capabilitiesClient) }
            scoped<MemoriesAlbums>() bind RemoteAlbums::class
            scoped {
                AndroidFolders(
                    get<Context>().contentResolver,
                    get(),
                    get(),
                )
            } bind LocalFolders::class
            scoped<LocalMediaPermissionCoordinator>()
            scoped<MemoriesFiles>()
            scoped { UploadSource(get<Context>().contentResolver) }
            scoped {
                Albums(
                    credentials = get(),
                    remote = get(),
                    local = get(),
                    library = get(),
                    permission = get<LocalMediaPermissionCoordinator>().mode,
                    scope = get(),
                    enqueueUploads = { ids, album -> get<UploadQueue>().submit(ids, album) },
                )
            }
            scoped { create(::uploadQueue) }
            scoped<MemoriesCloudTrash>() bind CloudTrash::class
            scoped<NextcloudTrashBin>()
            scoped { create(::mediaDeletion) }
            scoped<OperationStatusSource>(named<UploadQueue>()) { get<UploadQueue>() }
            scoped<OperationStatusSource>(named<Albums>()) { get<Albums>() }
            scoped<OperationStatusSource>(
                named<MediaDeletionRepository>(),
            ) { get<MediaDeletionRepository>() }
            scoped<MediaLibraryIndex>()
            scoped<LibraryPublicationStore>()
            scoped<TimelineProjectionStore>()
            scoped { create(::libraryCoordinator) }
            scoped<LocalMediaReader> { create(::mediaReader) }
            scoped<LocalMediaChangeObserver> {
                AndroidMediaStoreChangeObserver(get<Context>().contentResolver)
            }
            scoped<LocalMediaSource>()
            scoped<RemoteImageRepository>()
            scoped<MemoriesRepository>()
            viewModel { AlbumsViewModel(get()) }
            viewModel { (location: AlbumLocation) -> AlbumContentsViewModel(location, get()) }
            scoped(named<ThumbnailBatchLoader>()) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO)
            } onClose { it?.cancel() }
            scoped {
                ThumbnailBatchLoader(
                    get<RemoteImageRepository>()::ensureThumbnails,
                    get(named<ThumbnailBatchLoader>()),
                )
            }
            scoped<VideoPlayerFactory>()
            scoped<VideoFramesFactory>()
            scoped<MediaImageRequestFactory>()
            scoped<coil3.ImageLoader> { create(::imageLoader) } onClose { it?.shutdown() }
        }
    }

private fun memoriesClient(transport: NextcloudTransport, credentials: AccountCredentials) =
    MemoriesApiClient(transport) {
        credentials
    }
private fun albumsClient(transport: NextcloudTransport, credentials: AccountCredentials) =
    PhotosAlbumsDavClient(transport) { credentials }
private fun filesClient(transport: NextcloudTransport, credentials: AccountCredentials) =
    FilesDavClient(transport) { credentials }
private fun capabilitiesClient(transport: NextcloudTransport, credentials: AccountCredentials) =
    NextcloudCapabilitiesClient(transport) { credentials }
private fun uploadQueue(
    credentials: AccountCredentials,
    library: MediaLibraryIndex,
    scope: CoroutineScope,
    journal: UploadJournal,
    source: UploadSource,
    identities: MediaIdentityRegistry,
    albums: Albums,
    context: Context,
    coordinator: MediaLibraryCoordinator,
): UploadQueue =
    UploadQueue(
        credentials,
        library,
        scope,
        journal,
        source::snapshot,
        UploadFollowUps(
            UploadedCopiesFollowUp(identities),
            AlbumUploadFollowUp(albums),
        ),
        refreshLibrary = coordinator::refresh,
        schedule = { UploadWorker.schedule(context) },
        restart = { UploadWorker.schedule(context, now = true) },
    )

private fun mediaDeletion(
    credentials: AccountCredentials,
    library: MediaLibraryIndex,
    trash: CloudTrash,
    identities: MediaIdentityRegistry,
    context: Context,
    scope: CoroutineScope,
    coordinator: MediaLibraryCoordinator,
) = MediaDeletionRepository(
    credentials,
    library,
    trash,
    identities,
    AndroidPhoneTrash(context.contentResolver),
    scope,
    coordinator::refresh,
)
private fun libraryCoordinator(
    library: MediaLibraryIndex,
    credentials: AccountCredentials,
    remote: MemoriesRepository,
    local: LocalMediaSource,
    permissions: LocalMediaPermissionCoordinator,
) = MediaLibraryCoordinator(library, credentials, remote, local, permissions.mode)
private fun mediaReader(
    context: Context,
    db: NextGalleryDatabase,
    permissions: LocalMediaPermissionCoordinator,
) = AndroidMediaStoreReader(context.contentResolver, db.localMediaMetadataDao(), volumeVersions = {
    check(permissions.currentMode() == LocalMediaPermissionMode.Full) {
        "Full media permission required"
    }
    MediaStore
        .getExternalVolumeNames(
            context,
        ).associateWith { volume -> checkNotNull(MediaStore.getVersion(context, volume)) }
        .also {
            check(it.isNotEmpty()) { "No mounted media volumes" }
        }
})
private fun imageLoader(
    context: Context,
    thumbnails: ThumbnailBatchLoader,
    files: ThumbnailFileStore,
) = com.syrok0010.nextgallery.feature.images.createNextGalleryImageLoader(
    context,
    thumbnails::ensureAvailable,
    files,
)

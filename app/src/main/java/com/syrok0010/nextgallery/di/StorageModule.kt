package com.syrok0010.nextgallery.di

import android.content.Context
import com.syrok0010.nextgallery.core.database.NextGalleryDatabase
import com.syrok0010.nextgallery.core.database.RoomMediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.feature.images.RemoteImageCache
import com.syrok0010.nextgallery.feature.images.ThumbnailFileStore
import com.syrok0010.nextgallery.feature.library.local.LocalMediaProjectionRepository
import com.syrok0010.nextgallery.feature.library.local.LocalMediaProjectionStore
import com.syrok0010.nextgallery.feature.timeline.persistence.TimelineCacheRepository
import com.syrok0010.nextgallery.feature.upload.UploadJournal
import com.syrok0010.nextgallery.feature.upload.UploadQueueDatabase
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.plugin.module.dsl.*

internal fun storageModule() =
    module {
        single { create(::libraryDatabase) } onClose { it?.close() }
        single { create(::uploadDatabase) } onClose { it?.close() }
        single<UploadJournal>()
        single { create(::thumbnailFiles) }
        // Local-only thumbnails (e.g. queued uploads) can be rendered before an auth scope exists.
        single<coil3.ImageLoader> { create(::localImageLoader) } onClose { it?.shutdown() }
        single<RoomMediaIdentityRegistry>() bind MediaIdentityRegistry::class
        single<TimelineCacheRepository>()
        single<RemoteImageCache>()
        single<LocalMediaProjectionRepository>() bind LocalMediaProjectionStore::class
    }
private fun libraryDatabase(context: Context) = NextGalleryDatabase.create(context)
private fun uploadDatabase(context: Context) = UploadQueueDatabase.open(context)
private fun thumbnailFiles(context: Context) = ThumbnailFileStore(context)

private fun localImageLoader(context: Context, files: ThumbnailFileStore) =
    com.syrok0010.nextgallery.feature.images.createNextGalleryImageLoader(context, { false }, files)

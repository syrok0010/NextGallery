package com.syrok0010.nextgallery.feature.viewer

import android.content.ContentResolver
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.media.remoteCopy
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.network.dav.ReadFileResult
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ViewerGeoPoint(val latitude: Double, val longitude: Double)

data class ViewerMediaDetails(
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val exposureTimeSeconds: Double? = null,
    val aperture: Double? = null,
    val iso: Int? = null,
    val fileSizeBytes: Long? = null,
    val path: String? = null,
    val albums: List<String> = emptyList(),
    val location: ViewerGeoPoint? = null,
)

fun interface ViewerDetailsLoader {
    suspend fun load(item: MediaItem): ViewerMediaDetails
}

/** Loads the expensive metadata only when the details sheet is opened. */
class ViewerMediaDetailsRepository(
    private val contentResolver: ContentResolver,
    private val memories: MemoriesApiClient,
    private val files: FilesDavClient,
    private val credentials: () -> AccountCredentials,
) : ViewerDetailsLoader {
    private val albumMutex = Mutex()
    private var remoteAlbumNames: Map<Long, List<String>>? = null

    override suspend fun load(item: MediaItem): ViewerMediaDetails =
        withContext(Dispatchers.IO) {
            val local = item.localCopy?.let { readLocal(item) }
            val remote = item.remoteCopy?.let { readRemote(it.photoFileId, item.isVideo) }
            val merged = merge(local, remote)
            merged.copy(
                albums = (local?.albums.orEmpty() + remote?.albums.orEmpty()).distinct(),
                fileSizeBytes = merged.fileSizeBytes
                    ?: item.localCopy?.sizeBytes
                    ?: remote?.fileSizeBytes,
            )
        }

    private fun readLocal(item: MediaItem): ViewerMediaDetails? {
        val local = item.localCopy ?: return null
        val uri = local.contentUri.toUri()
        val exif = runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) }
        }.getOrNull()
        val relativePath = runCatching {
            contentResolver
                .query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
        }.getOrNull()
        return exif.toViewerDetails().copy(
            fileSizeBytes = local.sizeBytes,
            path = relativePath ?: local.contentUri,
            albums = relativePath
                ?.trimEnd('/')
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotBlank)
                ?.let(::listOf)
                .orEmpty(),
        )
    }

    private suspend fun readRemote(fileId: Long, isVideo: Boolean): ViewerMediaDetails {
        val info = memories.imageInfo(fileId)
        if (info.filename.isBlank()) return ViewerMediaDetails(fileSizeBytes = info.size)
        val account = credentials()
        val exif = if (isVideo) {
            ViewerMediaDetails()
        } else {
            runCatching {
                val result = files.readFile(
                    DavFilePath.of(account.userId, info.filename),
                    info.etag,
                ) { input -> ExifInterface(input).toViewerDetails() }
                when (result) {
                    is ReadFileResult.Read -> result.value
                    ReadFileResult.Changed -> ViewerMediaDetails()
                }
            }.getOrElse { ViewerMediaDetails() }
        }
        return exif.copy(
            fileSizeBytes = exif.fileSizeBytes ?: info.size,
            path = info.filename,
            albums = remoteAlbumsFor(fileId),
        )
    }

    private suspend fun remoteAlbumsFor(fileId: Long): List<String> {
        val cached = albumMutex.withLock { remoteAlbumNames } ?: run {
            val loaded = runCatching {
                memories
                    .albums()
                    .mapNotNull { album ->
                        val name = album.name.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        val location = album.clusterId ?: album.user?.let {
                            "$it/$name"
                        } ?: return@mapNotNull null
                        val days = memories.albumDays(location)
                        val ids = days.flatMap { it.detail }.map { it.fileid }.toMutableSet()
                        val missingDays = days.filter { it.count > it.detail.size }.map { it.dayid }
                        missingDays.chunked(10).forEach { chunk ->
                            ids += memories
                                .albumPhotos(
                                    chunk.joinToString(","),
                                    location,
                                ).map { it.fileid }
                        }
                        fileId.takeIf { it in ids }?.let { fileId to name }
                    }.groupBy({ it.first }, { it.second })
            }.getOrDefault(emptyMap())
            albumMutex.withLock {
                remoteAlbumNames = loaded
                loaded
            }
        }
        return cached[fileId].orEmpty()
    }

    private fun merge(first: ViewerMediaDetails?, second: ViewerMediaDetails?): ViewerMediaDetails =
        ViewerMediaDetails(
            cameraModel = first?.cameraModel ?: second?.cameraModel,
            lensModel = first?.lensModel ?: second?.lensModel,
            exposureTimeSeconds = first?.exposureTimeSeconds ?: second?.exposureTimeSeconds,
            aperture = first?.aperture ?: second?.aperture,
            iso = first?.iso ?: second?.iso,
            fileSizeBytes = second?.fileSizeBytes ?: first?.fileSizeBytes,
            path = second?.path ?: first?.path,
            location = first?.location ?: second?.location,
        )
}

@Suppress("DEPRECATION")
private fun ExifInterface?.toViewerDetails(): ViewerMediaDetails {
    if (this == null) return ViewerMediaDetails()
    val make = getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
    val model = getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
    val camera = listOf(
        make,
        model,
    ).filter(String::isNotBlank).distinct().joinToString(" ").ifBlank {
        null
    }
    val lens = getAttribute(ExifInterface.TAG_LENS_MODEL)
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val point = latLong?.takeIf { it.size == 2 }?.let { ViewerGeoPoint(it[0], it[1]) }
    return ViewerMediaDetails(
        cameraModel = camera,
        lensModel = lens,
        exposureTimeSeconds = getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN)
            .takeUnless(Double::isNaN),
        aperture = getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN)
            .takeUnless(Double::isNaN),
        iso = getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }
            ?: getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0).takeIf { it > 0 },
        location = point,
    )
}

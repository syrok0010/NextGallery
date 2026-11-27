package com.syrok0010.nextgallery.feature.viewer

import android.content.ContentResolver
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.media.remoteCopy
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesImageInfoDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

data class ViewerGeoPoint(val latitude: Double, val longitude: Double)

data class ViewerMediaDetails(
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val focalLengthMm: Double? = null,
    val exposureTimeSeconds: Double? = null,
    val aperture: Double? = null,
    val iso: Int? = null,
    val fileSizeBytes: Long? = null,
    val localPath: String? = null,
    val remotePath: String? = null,
    val albums: List<String> = emptyList(),
    val location: ViewerGeoPoint? = null,
    val locationLabel: String? = null,
)

fun interface ViewerDetailsLoader {
    suspend fun load(item: MediaItem): ViewerMediaDetails
}

/** Loads and caches viewer metadata from local EXIF and Memories `image/info`. */
class ViewerMediaDetailsRepository(
    private val contentResolver: ContentResolver,
    private val memories: MemoriesApiClient,
) : ViewerDetailsLoader {
    private val cacheMutex = Mutex()
    private val cache = object : LinkedHashMap<MediaId, ViewerMediaDetails>(
        CACHE_CAPACITY,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<MediaId, ViewerMediaDetails>,
        ): Boolean = size > CACHE_CAPACITY
    }

    override suspend fun load(item: MediaItem): ViewerMediaDetails {
        cacheMutex.withLock { cache[item.mediaId] }?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            val local = item.localCopy?.let { readLocal(item) }
            val remote = item.remoteCopy?.let { readRemote(it.photoFileId) }
            mergeViewerMediaDetails(local, remote).let { merged ->
                merged.copy(
                    fileSizeBytes = merged.fileSizeBytes ?: item.localCopy?.sizeBytes,
                )
            }
        }
        cacheMutex.withLock { cache[item.mediaId] = loaded }
        return loaded
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
        val directory = normalizeLocalDirectory(relativePath)
        return exif.toViewerDetails().copy(
            fileSizeBytes = local.sizeBytes,
            localPath = directory,
            albums = listOfNotNull(localAlbumName(directory)),
        )
    }

    private suspend fun readRemote(fileId: Long): ViewerMediaDetails? =
        runCatching {
            memories.imageInfo(fileId, clusters = "albums").toViewerDetails()
        }.getOrNull()

    private companion object {
        const val CACHE_CAPACITY = 64
    }
}

internal fun MemoriesImageInfoDto.toViewerDetails(): ViewerMediaDetails {
    val exifDetails = exif.toViewerDetails()
    val albums = clusters
        ?.albums
        .orEmpty()
        .mapNotNull { it.name.trim().takeIf(String::isNotBlank) }
        .distinct()
    return exifDetails.copy(
        fileSizeBytes = size,
        remotePath = normalizeRemoteDirectory(filename),
        albums = albums,
        locationLabel = address?.trim()?.takeIf(String::isNotBlank),
    )
}

internal fun JsonObject?.toViewerDetails(): ViewerMediaDetails {
    if (this == null) return ViewerMediaDetails()
    val make = stringValue("Make")
    val model = stringValue("Model")
    val camera = formatCameraName(make, model)
    val lens = stringValue("LensModel")
    val focalLength = doubleValue("FocalLength")
    val exposure = exposureValue("ExposureTime")
        ?: exposureValue("ShutterSpeed")
        ?: exposureValue("ShutterSpeedValue")
    val aperture = doubleValue("FNumber")
        ?: doubleValue("Aperture")
        ?: doubleValue("ApertureValue")
    val iso = intValue("ISO")
    val latitude = doubleValue("GPSLatitude")
    val longitude = doubleValue("GPSLongitude")
    val location = if (latitude != null && longitude != null) {
        ViewerGeoPoint(latitude, longitude)
    } else {
        null
    }
    return ViewerMediaDetails(
        cameraModel = camera,
        lensModel = lens,
        focalLengthMm = focalLength,
        exposureTimeSeconds = exposure,
        aperture = aperture,
        iso = iso,
        location = location,
    )
}

internal fun formatCameraName(make: String?, model: String?): String? {
    val cleanMake = make?.trim().orEmpty()
    val cleanModel = model?.trim().orEmpty()
    return when {
        cleanMake.isBlank() && cleanModel.isBlank() -> null
        cleanMake.isBlank() -> cleanModel
        cleanModel.isBlank() -> cleanMake
        cleanModel.startsWith(cleanMake, ignoreCase = true) -> cleanModel
        else -> "$cleanMake $cleanModel"
    }
}

internal fun normalizeLocalDirectory(relativePath: String?): String? =
    relativePath?.trim()?.trim('/')?.takeIf(String::isNotBlank)

internal fun normalizeRemoteDirectory(filePath: String?): String? {
    val trimmed = filePath?.trim()?.takeIf(String::isNotBlank) ?: return null
    val slashIndex = trimmed.lastIndexOf('/')
    if (slashIndex < 0) return null
    val parent = trimmed.substring(0, slashIndex).trim('/')
    return parent.ifBlank { "/" }
}

internal fun localAlbumName(localDirectory: String?): String? =
    localDirectory?.substringAfterLast('/')?.takeIf(String::isNotBlank)

internal fun mergeViewerMediaDetails(
    local: ViewerMediaDetails?,
    remote: ViewerMediaDetails?,
): ViewerMediaDetails =
    ViewerMediaDetails(
        cameraModel = local?.cameraModel ?: remote?.cameraModel,
        lensModel = local?.lensModel ?: remote?.lensModel,
        focalLengthMm = local?.focalLengthMm ?: remote?.focalLengthMm,
        exposureTimeSeconds = local?.exposureTimeSeconds ?: remote?.exposureTimeSeconds,
        aperture = local?.aperture ?: remote?.aperture,
        iso = local?.iso ?: remote?.iso,
        fileSizeBytes = local?.fileSizeBytes ?: remote?.fileSizeBytes,
        localPath = local?.localPath,
        remotePath = remote?.remotePath,
        albums = (local?.albums.orEmpty() + remote?.albums.orEmpty()).distinct(),
        location = local?.location ?: remote?.location,
        locationLabel = local?.locationLabel ?: remote?.locationLabel,
    )

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.doubleValue(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    primitive.doubleOrNull?.takeIf { it.isFinite() }?.let { return it }
    val raw = primitive.contentOrNull?.trim() ?: return null
    return NUMBER_PATTERN.find(raw)?.value?.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun JsonObject.intValue(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    primitive.intOrNull?.takeIf { it > 0 }?.let { return it }
    val raw = primitive.contentOrNull?.trim() ?: return null
    return NUMBER_PATTERN.find(raw)?.value?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
}

private fun JsonObject.exposureValue(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    primitive.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 }?.let { return it }
    val raw = primitive.contentOrNull?.trim() ?: return null
    val fraction = FRACTION_PATTERN.find(raw)
    if (fraction != null) {
        val numerator = fraction.groupValues[1].toDoubleOrNull()
        val denominator = fraction.groupValues[2].toDoubleOrNull()
        if (numerator != null && denominator != null && denominator > 0.0) {
            return (numerator / denominator).takeIf { it.isFinite() && it > 0.0 }
        }
    }
    return NUMBER_PATTERN.find(raw)?.value?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}

private val NUMBER_PATTERN = Regex("""-?\d+(?:\.\d+)?""")
private val FRACTION_PATTERN = Regex("""(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)""")

@Suppress("DEPRECATION")
private fun ExifInterface?.toViewerDetails(): ViewerMediaDetails {
    if (this == null) return ViewerMediaDetails()
    val camera = formatCameraName(
        getAttribute(ExifInterface.TAG_MAKE),
        getAttribute(ExifInterface.TAG_MODEL),
    )
    val lens = getAttribute(ExifInterface.TAG_LENS_MODEL)
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val point = latLong?.takeIf { it.size == 2 }?.let { ViewerGeoPoint(it[0], it[1]) }
    return ViewerMediaDetails(
        cameraModel = camera,
        lensModel = lens,
        focalLengthMm = getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN)
            .takeUnless { it.isNaN() || it <= 0.0 },
        exposureTimeSeconds = getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN)
            .takeUnless { it.isNaN() || it <= 0.0 },
        aperture = getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN)
            .takeUnless { it.isNaN() || it <= 0.0 },
        iso = getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }
            ?: getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0).takeIf { it > 0 },
        location = point,
    )
}

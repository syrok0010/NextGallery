package com.syrok0010.nextgallery.data.local

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.core.net.toUri
import java.text.SimpleDateFormat
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class AndroidMediaStoreReader(
    private val contentResolver: ContentResolver,
) : LocalMediaReader {
    override fun readBatches(batchSize: Int): Flow<LocalMediaBatch> = flow {
        require(batchSize > 0)
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Video.VideoColumns.DURATION,
        )
        val selection = """
            ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)
            AND ${MediaStore.MediaColumns.IS_PENDING} = 0
            AND ${MediaStore.MediaColumns.IS_TRASHED} = 0
        """.trimIndent()
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val sortOrder = """
            CASE
                WHEN ${MediaStore.Images.ImageColumns.DATE_TAKEN} > 0
                    THEN ${MediaStore.Images.ImageColumns.DATE_TAKEN} / 1000
                WHEN ${MediaStore.MediaColumns.DATE_MODIFIED} > 0
                    THEN ${MediaStore.MediaColumns.DATE_MODIFIED}
                ELSE ${MediaStore.MediaColumns.DATE_ADDED}
            END DESC,
            ${MediaStore.Files.FileColumns._ID} DESC
        """.trimIndent()

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
            val batch = ArrayList<LocalMediaMetadata>(batchSize)
            var indexedCount = 0

            while (cursor.moveToNext()) {
                val isVideo = cursor.getInt(typeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(
                    if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
                val width = cursor.nullableInt(widthColumn)
                val height = cursor.nullableInt(heightColumn)
                val orientation = cursor.nullableInt(orientationColumn)
                val orientedWidth = if (orientation.rotatesDimensions()) height else width
                val orientedHeight = if (orientation.rotatesDimensions()) width else height
                val dateTakenMillis = cursor.nullableLong(takenColumn)
                batch += LocalMediaMetadata(
                    contentUri = uri.toString(),
                    displayName = cursor.getString(nameColumn) ?: id.toString(),
                    mimeType = cursor.nullableString(mimeColumn),
                    width = orientedWidth,
                    height = orientedHeight,
                    sizeBytes = cursor.nullableLong(sizeColumn),
                    dateTakenMillis = dateTakenMillis,
                    memoriesTimelineEpochSeconds = null,
                    imageUniqueId = null,
                    dateModifiedSeconds = cursor.nullableLong(modifiedColumn),
                    dateAddedSeconds = cursor.nullableLong(addedColumn),
                    durationMillis = cursor.nullableLong(durationColumn),
                    isVideo = isVideo,
                )
                indexedCount += 1
                if (batch.size == batchSize) {
                    emit(
                        LocalMediaBatch(
                            metadata = batch.enrichExif(),
                            progress = LocalMediaIndexProgress(indexedCount, cursor.count),
                        ),
                    )
                    batch.clear()
                }
            }
            if (batch.isNotEmpty() || cursor.count == 0) {
                emit(
                    LocalMediaBatch(
                        metadata = batch.enrichExif(),
                        progress = LocalMediaIndexProgress(indexedCount, cursor.count),
                    ),
                )
            }
        } ?: emit(
            LocalMediaBatch(
                metadata = emptyList(),
                progress = LocalMediaIndexProgress(indexedCount = 0, totalCount = 0),
            ),
        )
    }.flowOn(Dispatchers.IO)

    private suspend fun List<LocalMediaMetadata>.enrichExif(): List<LocalMediaMetadata> =
        chunked(EXIF_CONCURRENCY).flatMap { chunk ->
            coroutineScope {
                chunk.map { metadata ->
                    async(Dispatchers.IO) { metadata.withExif() }
                }.awaitAll()
            }
        }

    private fun LocalMediaMetadata.withExif(): LocalMediaMetadata {
        val exif = if (isVideo) null else readExif(contentUri)
        return copy(
            memoriesTimelineEpochSeconds = memoriesTimelineEpochSeconds(
                exifDateTime = exif?.getAttribute(ExifInterface.TAG_DATETIME),
                dateTakenMillis = dateTakenMillis,
            ),
            imageUniqueId = exif?.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID),
        )
    }

    private fun android.database.Cursor.nullableString(column: Int): String? =
        if (isNull(column)) null else getString(column)

    private fun android.database.Cursor.nullableInt(column: Int): Int? =
        if (isNull(column)) null else getInt(column)

    private fun android.database.Cursor.nullableLong(column: Int): Long? =
        if (isNull(column)) null else getLong(column)

    private fun Int?.rotatesDimensions(): Boolean = this == 90 || this == 270

    private fun readExif(contentUri: String): ExifInterface? = runCatching {
        contentResolver.openFileDescriptor(contentUri.toUri(), "r")?.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor)
        }
    }.getOrNull()

    private fun memoriesTimelineEpochSeconds(
        exifDateTime: String?,
        dateTakenMillis: Long?,
    ): Long? {
        if (exifDateTime != null) {
            runCatching {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss").apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }.parse(exifDateTime)?.time?.div(1_000)
            }.getOrNull()?.let { return it }
        }
        return dateTakenMillis?.takeIf { it > 0 }?.let { timestamp ->
            (timestamp + TimeZone.getDefault().getOffset(timestamp)) / 1_000
        }
    }

    private companion object {
        const val EXIF_CONCURRENCY = 8
    }
}

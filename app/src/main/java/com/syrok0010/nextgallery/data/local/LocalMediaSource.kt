package com.syrok0010.nextgallery.data.local

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.syrok0010.nextgallery.data.cache.TimelineCacheRepository
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalMediaRow(
    val contentUri: String,
    val displayName: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long?,
    val dateTakenMillis: Long?,
    val dateModifiedSeconds: Long?,
    val dateAddedSeconds: Long?,
    val durationMillis: Long?,
    val isVideo: Boolean,
)

fun interface LocalMediaGateway {
    suspend fun readAll(): List<LocalMediaRow>
}

class LocalMediaSource(
    private val gateway: LocalMediaGateway,
    private val resolveMediaIds: suspend (Collection<String>) -> Map<String, com.syrok0010.nextgallery.domain.media.MediaId>,
) {
    constructor(
        gateway: LocalMediaGateway,
        cacheRepository: TimelineCacheRepository,
    ) : this(gateway, cacheRepository::resolveLocalMediaIds)

    suspend fun readAll(): List<MediaItem> {
        val rows = gateway.readAll()
        val mediaIds = resolveMediaIds(rows.map { it.contentUri })
        return rows.mapNotNull { row ->
            val timestamp = row.timelineEpochSeconds() ?: return@mapNotNull null
            val dayId = Math.floorDiv(timestamp, SECONDS_PER_DAY).toInt()
            MediaItem(
                mediaId = checkNotNull(mediaIds[row.contentUri]),
                fileId = row.contentUri.hashCode().toLong(),
                dayId = dayId,
                day = LocalDate.ofEpochDay(dayId.toLong()),
                displayName = row.displayName,
                mimeType = row.mimeType,
                width = row.width,
                height = row.height,
                etag = null,
                livePhotoId = null,
                auid = null,
                buid = null,
                sharedBy = null,
                takenAtEpochSeconds = timestamp,
                isVideo = row.isVideo,
                videoDurationSeconds = row.durationMillis?.takeIf { it > 0 }?.div(1_000),
                isFavorite = false,
                isHidden = false,
                assetRef = MediaAssetRef.LocalContent(row.contentUri),
            )
        }.sortedWith(compareByDescending<MediaItem> { it.takenAtEpochSeconds }.thenByDescending { it.mediaId.value })
    }

    private fun LocalMediaRow.timelineEpochSeconds(): Long? =
        dateTakenMillis?.takeIf { it > 0 }?.div(1_000)
            ?: dateModifiedSeconds?.takeIf { it > 0 }
            ?: dateAddedSeconds?.takeIf { it > 0 }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}

class AndroidMediaStoreGateway(
    private val contentResolver: ContentResolver,
) : LocalMediaGateway {
    override suspend fun readAll(): List<LocalMediaRow> = withContext(Dispatchers.IO) {
        readAllBlocking()
    }

    private fun readAllBlocking(): List<LocalMediaRow> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Video.VideoColumns.DURATION,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val rows = mutableListOf<LocalMediaRow>()

        contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)

            while (cursor.moveToNext()) {
                val isVideo = cursor.getInt(typeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(
                    if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
                rows += LocalMediaRow(
                    contentUri = uri.toString(),
                    displayName = cursor.getString(nameColumn) ?: id.toString(),
                    mimeType = cursor.nullableString(mimeColumn),
                    width = cursor.nullableInt(widthColumn),
                    height = cursor.nullableInt(heightColumn),
                    sizeBytes = cursor.nullableLong(sizeColumn),
                    dateTakenMillis = cursor.nullableLong(takenColumn),
                    dateModifiedSeconds = cursor.nullableLong(modifiedColumn),
                    dateAddedSeconds = cursor.nullableLong(addedColumn),
                    durationMillis = cursor.nullableLong(durationColumn),
                    isVideo = isVideo,
                )
            }
        }
        return rows
    }

    private fun android.database.Cursor.nullableString(column: Int): String? =
        if (isNull(column)) null else getString(column)

    private fun android.database.Cursor.nullableInt(column: Int): Int? =
        if (isNull(column)) null else getInt(column)

    private fun android.database.Cursor.nullableLong(column: Int): Long? =
        if (isNull(column)) null else getLong(column)
}

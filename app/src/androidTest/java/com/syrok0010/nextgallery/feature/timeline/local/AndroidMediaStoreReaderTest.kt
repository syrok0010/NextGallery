package com.syrok0010.nextgallery.feature.library.local

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.core.database.NextGalleryDatabase
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaStoreReaderTest {
    @Test
    fun readsImagesAndVideosInBatches() = runBlocking {
        val provider = FixtureMediaProvider()
        val resolver = ContentResolver.wrap(provider)
        val reader = AndroidMediaStoreReader(resolver)

        val batches = reader.readBatches(batchSize = 2).toList()

        assertEquals(listOf(2, 1), batches.map { it.metadata.size })
        assertEquals(listOf(2, 3), batches.map { it.progress.indexedCount })
        assertEquals(listOf(3, 3), batches.map { it.progress.totalCount })
        assertEquals(
            listOf(
                "content://media/external/images/media/30",
                "content://media/external/video/media/20",
                "content://media/external/images/media/10",
            ),
            batches.flatMap { it.metadata }.map { it.contentUri },
        )
        assertEquals(listOf(false, true, false), batches.flatMap { it.metadata }.map { it.isVideo })
        assertEquals(
            listOf(3_024, 4_032, 3_024),
            batches.flatMap { it.metadata }.map { it.width },
        )
        assertEquals(
            listOf(4_032, 3_024, 4_032),
            batches.flatMap { it.metadata }.map { it.height },
        )
        // Without EXIF the timeline time comes from DATE_TAKEN; the conversion is CanonicalTimelineTimeTest's.
        assertEquals(
            canonicalTimelineSeconds(exifDateTime = null, dateTakenMillis = 1_700_000_000_030L),
            batches.first().metadata.first().memoriesTimelineEpochSeconds,
        )
    }

    @Test
    fun readsNewestFirstAndSkipsPendingTrashedAndNonMediaFiles() = runBlocking {
        val provider = FixtureMediaProvider(
            rows = listOf(
                FixtureRow(id = 5, mediaType = VIDEO),
                FixtureRow(id = 10),
                FixtureRow(id = 20, pending = true),
                FixtureRow(id = 30, trashed = true),
                // Without DATE_TAKEN the modification time orders the file.
                FixtureRow(id = 40, dateTakenMillis = 0, dateModifiedSeconds = 1_800_000_000),
                FixtureRow(id = 50, dateTakenMillis = 1_600_000_000_000),
                FixtureRow(id = 60, mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_NONE),
            ),
        )

        val batches = AndroidMediaStoreReader(ContentResolver.wrap(provider)).readBatches(batchSize = 10).toList()

        // 5 and 10 were taken in the same second; the newer row comes first.
        assertEquals(
            listOf(40L, 10L, 5L, 50L),
            batches.flatMap { it.metadata }.map { it.contentUri.substringAfterLast('/').toLong() },
        )
        assertEquals(4, batches.last().progress.totalCount)
    }

    @Test
    fun readsMemoriesDateAndImageUniqueIdFromExif() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val jpeg = File(context.cacheDir, "media-reader-exif-fixture.jpg")
        FileOutputStream(jpeg).use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).run {
                compress(Bitmap.CompressFormat.JPEG, 100, output)
                recycle()
            }
        }
        ExifInterface(jpeg.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME, "2024:01:02 03:04:05")
            setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, "camera-unique-42")
            saveAttributes()
        }

        try {
            val provider = FixtureMediaProvider(jpeg)
            val database = Room.inMemoryDatabaseBuilder(context, NextGalleryDatabase::class.java).build()
            try {
                var version = "v1"
                fun reader() = AndroidMediaStoreReader(
                    ContentResolver.wrap(provider), database.localMediaMetadataDao(),
                    volumeVersions = { mapOf("external_primary" to version) },
                )
                val firstImage = reader().readBatches(3).toList().single().metadata.first()
                assertEquals(1_704_164_645L, firstImage.memoriesTimelineEpochSeconds)
                assertEquals("camera-unique-42", firstImage.imageUniqueId)
                assertEquals(2, provider.fileOpens.get())
                reader().readBatches(2).toList()
                assertEquals(2, provider.fileOpens.get())
                provider.generation = 2
                reader().readBatches(3).toList()
                assertEquals(4, provider.fileOpens.get())
                version = "v2"
                reader().readBatches(3).toList()
                assertEquals(6, provider.fileOpens.get())
            } finally {
                database.close()
            }
        } finally {
            jpeg.delete()
        }
    }

    @Test
    fun nullCursorDoesNotReportSuccessfulEmptyLibrary() = runBlocking {
        val reader = AndroidMediaStoreReader(ContentResolver.wrap(FixtureMediaProvider(nullCursor = true)))
        var failed = false
        try {
            reader.readBatches(200).toList()
        } catch (expected: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
    }

    private data class FixtureRow(
        val id: Long,
        val mediaType: Int = IMAGE,
        val dateTakenMillis: Long = 1_700_000_000_000L + id,
        val dateModifiedSeconds: Long = 1_700_000_000L + id,
        val pending: Boolean = false,
        val trashed: Boolean = false,
    )

    /** MediaStore files table in in-memory SQLite, so the reader's selection and sort order really run. */
    private class FixtureMediaProvider(
        private val mediaFile: File? = null,
        private val nullCursor: Boolean = false,
        rows: List<FixtureRow> = listOf(FixtureRow(30), FixtureRow(20, mediaType = VIDEO), FixtureRow(10)),
    ) : ContentProvider() {
        val fileOpens = AtomicInteger()
        var generation = 1L
        private val database = SQLiteDatabase.create(null).apply {
            execSQL(
                """
                CREATE TABLE files (
                    ${MediaStore.Files.FileColumns._ID} INTEGER PRIMARY KEY,
                    ${MediaStore.MediaColumns.VOLUME_NAME} TEXT,
                    ${MediaStore.MediaColumns.GENERATION_MODIFIED} INTEGER,
                    ${MediaStore.Files.FileColumns.MEDIA_TYPE} INTEGER,
                    ${MediaStore.MediaColumns.DISPLAY_NAME} TEXT,
                    ${MediaStore.MediaColumns.MIME_TYPE} TEXT,
                    ${MediaStore.MediaColumns.WIDTH} INTEGER,
                    ${MediaStore.MediaColumns.HEIGHT} INTEGER,
                    ${MediaStore.MediaColumns.ORIENTATION} INTEGER,
                    ${MediaStore.MediaColumns.SIZE} INTEGER,
                    ${MediaStore.Images.ImageColumns.DATE_TAKEN} INTEGER,
                    ${MediaStore.MediaColumns.DATE_MODIFIED} INTEGER,
                    ${MediaStore.MediaColumns.DATE_ADDED} INTEGER,
                    ${MediaStore.Video.VideoColumns.DURATION} INTEGER,
                    ${MediaStore.MediaColumns.IS_PENDING} INTEGER,
                    ${MediaStore.MediaColumns.IS_TRASHED} INTEGER
                )
                """.trimIndent(),
            )
            rows.forEach { insert("files", null, it.values()) }
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            if (nullCursor) return null
            database.execSQL("UPDATE files SET ${MediaStore.MediaColumns.GENERATION_MODIFIED} = $generation")
            return database.query("files", projection, selection, selectionArgs, null, null, sortOrder)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor? = query(
            uri = uri,
            projection = projection,
            selection = queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
            selectionArgs = queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
            sortOrder = queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
        )

        override fun getType(uri: Uri): String? = null
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            fileOpens.incrementAndGet()
            val file = mediaFile ?: throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        private fun FixtureRow.values() = ContentValues().apply {
            val video = mediaType == VIDEO
            put(MediaStore.Files.FileColumns._ID, id)
            put(MediaStore.MediaColumns.VOLUME_NAME, "external_primary")
            put(MediaStore.Files.FileColumns.MEDIA_TYPE, mediaType)
            put(MediaStore.MediaColumns.DISPLAY_NAME, "media-$id")
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.WIDTH, 4_032)
            put(MediaStore.MediaColumns.HEIGHT, 3_024)
            put(
                MediaStore.MediaColumns.ORIENTATION,
                when (id) {
                    30L -> 90
                    10L -> 270
                    else -> 0
                },
            )
            put(MediaStore.MediaColumns.SIZE, 1_000L + id)
            put(MediaStore.Images.ImageColumns.DATE_TAKEN, dateTakenMillis)
            put(MediaStore.MediaColumns.DATE_MODIFIED, dateModifiedSeconds)
            put(MediaStore.MediaColumns.DATE_ADDED, 1_600_000_000L + id)
            if (video) put(MediaStore.Video.VideoColumns.DURATION, 2_500L)
            put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
            put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0)
        }
    }

    private companion object {
        const val IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        const val VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
    }
}

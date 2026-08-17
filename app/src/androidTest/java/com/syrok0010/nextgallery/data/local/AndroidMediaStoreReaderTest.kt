package com.syrok0010.nextgallery.data.local

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaStoreReaderTest {
    @Test
    fun readsImagesAndVideosInBatchesAndExcludesPendingAndTrashedMedia() = runBlocking {
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
        assertTrue(provider.observedSelection.orEmpty().contains("${MediaStore.MediaColumns.IS_PENDING} = 0"))
        assertTrue(provider.observedSelection.orEmpty().contains("${MediaStore.MediaColumns.IS_TRASHED} = 0"))
        assertTrue(provider.observedSortOrder.orEmpty().contains("CASE"))
    }

    private class FixtureMediaProvider : ContentProvider() {
        var observedSelection: String? = null
        var observedSortOrder: String? = null

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            observedSelection = selection
            observedSortOrder = sortOrder
            return fixtureCursor(requireNotNull(projection))
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor = query(
            uri = uri,
            projection = projection,
            selection = queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
            selectionArgs = queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
            sortOrder = queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
        )

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        private fun fixtureCursor(projection: Array<out String>) = MatrixCursor(projection).apply {
            addMediaRow(projection, id = 30, mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            addMediaRow(projection, id = 20, mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            addMediaRow(projection, id = 10, mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
        }

        private fun MatrixCursor.addMediaRow(
            projection: Array<out String>,
            id: Long,
            mediaType: Int,
        ) {
            val values = projection.map { column ->
                when (column) {
                    MediaStore.Files.FileColumns._ID -> id
                    MediaStore.Files.FileColumns.MEDIA_TYPE -> mediaType
                    MediaStore.MediaColumns.DISPLAY_NAME -> "media-$id"
                    MediaStore.MediaColumns.MIME_TYPE -> if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                        "video/mp4"
                    } else {
                        "image/jpeg"
                    }
                    MediaStore.MediaColumns.WIDTH -> 4_032
                    MediaStore.MediaColumns.HEIGHT -> 3_024
                    MediaStore.MediaColumns.SIZE -> 1_000L + id
                    MediaStore.Images.ImageColumns.DATE_TAKEN -> 1_700_000_000_000L + id
                    MediaStore.MediaColumns.DATE_MODIFIED -> 1_700_000_000L + id
                    MediaStore.MediaColumns.DATE_ADDED -> 1_600_000_000L + id
                    MediaStore.Video.VideoColumns.DURATION -> if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                        2_500L
                    } else {
                        null
                    }
                    else -> null
                }
            }
            addRow(values)
        }
    }
}

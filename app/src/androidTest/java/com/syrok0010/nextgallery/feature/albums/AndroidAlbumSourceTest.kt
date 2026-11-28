package com.syrok0010.nextgallery.feature.albums

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.database.NextGalleryDatabase
import com.syrok0010.nextgallery.core.database.RoomMediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.feature.library.local.AndroidMediaStoreReader
import com.syrok0010.nextgallery.feature.library.local.LocalMediaChangeObserver
import com.syrok0010.nextgallery.feature.library.local.LocalMediaProjectionRepository
import com.syrok0010.nextgallery.feature.library.local.LocalMediaSource
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAlbumSourceTest {
    @Test fun sameNamedFoldersInDifferentPathsHaveSeparateCountsAndUsableCovers() = runBlocking {
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        val epoch = Instant.parse("2026-11-07T22:02:00Z").epochSecond
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = targetContext.contentResolver
        val database = Room.inMemoryDatabaseBuilder(targetContext, NextGalleryDatabase::class.java).build()
        val identities = RoomMediaIdentityRegistry(database)
        val projections = LocalMediaProjectionRepository(database)
        val inserted = mutableListOf<Uri>()
        val prefix = "Pictures/NextGalleryCatalogTest-${System.nanoTime()}"
        fun insert(path: String) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "fixture-${inserted.size}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, path)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            inserted += uri
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
            resolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:08 01:02:00")
                    setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+03:00")
                    saveAttributes()
                }
            }
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                    put(MediaStore.Images.Media.DATE_TAKEN, epoch * 1_000)
                },
                null,
                null,
            )
        }
        try {
            insert("$prefix/one/Отпуск/")
            insert("$prefix/one/Отпуск/")
            insert("$prefix/two/Отпуск/")
            insert("$prefix/one/Отпуск/Nested/")
            val source = LocalMediaSource(
                reader = AndroidMediaStoreReader(resolver, database.localMediaMetadataDao()),
                projectionStore = projections,
                identityRegistry = identities,
                changeObserver = LocalMediaChangeObserver { emptyFlow() },
            )
            val indexed = source.updates().first { it.progress == null && it.processed != null }.items
            val persisted = projections.loadLocalMediaProjection()
            assertEquals(indexed.size, persisted.size)
            val albums = localAlbumSummaries(persisted).filter { it.detail.contains(prefix) && it.name == "Отпуск" }
            assertEquals(2, albums.size)
            assertEquals(setOf(1, 2), albums.map { it.count }.toSet())
            assertEquals(2, albums.map { it.id }.toSet().size)
            assertTrue(albums.all { it.name == "Отпуск" })
            albums.forEach { album ->
                val contents = folderItems(persisted, album.location as AlbumLocation.Folder)
                assertEquals(album.count, contents.size)
                contents.forEach { item ->
                    assertEquals(epoch, item.takenAtEpochSeconds)
                    assertEquals(LocalDate.parse("2026-11-08"), item.day)
                    resolver.openInputStream(Uri.parse((item.assetRef as MediaAssetRef.LocalContent).contentUri))!!.close()
                }
                resolver.openInputStream(Uri.parse((album.cover as AlbumCover.Local).uri))!!.use {
                    assertTrue(it.read() >= 0)
                }
            }
        } finally {
            database.close()
            inserted.forEach { resolver.delete(it, null, null) }
            TimeZone.setDefault(previousZone)
        }
    }
}

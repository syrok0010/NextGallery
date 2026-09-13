package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import org.koin.core.context.GlobalContext
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaProjectionStore
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.last
import org.junit.Assert.*
import org.junit.Test

class AndroidAlbumSourceTest {
    @Test fun sameNamedFoldersInDifferentPathsHaveSeparateCountsAndUsableCovers() = runBlocking {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
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
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        try {
            insert("$prefix/one/Отпуск/"); insert("$prefix/one/Отпуск/"); insert("$prefix/two/Отпуск/")
            insert("$prefix/one/Отпуск/Nested/")
            val albums = AndroidAlbumSource(resolver).load().filter { it.detail.contains(prefix) && it.name == "Отпуск" }
            assertEquals(2, albums.size)
            assertEquals(setOf(1, 2), albums.map { it.count }.toSet())
            assertEquals(2, albums.map { it.id }.toSet().size)
            assertTrue(albums.all { it.name == "Отпуск" })
            albums.forEach { album ->
                val contents = AndroidAlbumContents(resolver, GlobalContext.get().get(), GlobalContext.get().get()).load(album.location as AlbumLocation.Folder).last()
                assertEquals(album.count, contents.items.size)
                assertEquals(album.count, contents.total)
                val indexed = contents.items.map { item ->
                    val asset = item.assetRef as MediaAssetRef.LocalContent
                    val aggregateUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        Uri.parse(asset.contentUri).lastPathSegment!!.toLong()).toString()
                    item.copy(dayId = 42, takenAtEpochSeconds = 42 * 86_400L,
                        assetRef = asset.copy(contentUri = aggregateUri))
                }
                val cache = object : LocalMediaProjectionStore {
                    override suspend fun loadLocalMediaProjection() = indexed
                    override suspend fun saveLocalMediaBatch(items: List<MediaItem>) = error("Read only")
                    override suspend fun finishLocalMediaReconciliation(contentUris: Set<String>) = error("Read only")
                }
                val cachedContents = AndroidAlbumContents(resolver, GlobalContext.get().get(), cache)
                    .load(album.location).last()
                assertTrue(cachedContents.items.all { it.dayId == 42 && it.takenAtEpochSeconds == 42 * 86_400L })
                contents.items.forEach { item ->
                    resolver.openInputStream(Uri.parse((item.assetRef as MediaAssetRef.LocalContent).contentUri))!!.close()
                }
                resolver.openInputStream(Uri.parse((album.cover as AlbumCover.Local).uri))!!.use { assertTrue(it.read() >= 0) }
            }
        } finally { inserted.forEach { resolver.delete(it, null, null) } }
    }
}

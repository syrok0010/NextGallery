package com.syrok0010.nextgallery.feature.albums

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
            val albums = AndroidAlbumSource(resolver).load().filter { it.detail.contains(prefix) }
            assertEquals(2, albums.size)
            assertEquals(setOf(1, 2), albums.map { it.count }.toSet())
            assertEquals(2, albums.map { it.id }.toSet().size)
            assertTrue(albums.all { it.name == "Отпуск" })
            albums.forEach { album ->
                resolver.openInputStream(Uri.parse((album.cover as AlbumCover.Local).uri))!!.use { assertTrue(it.read() >= 0) }
            }
        } finally { inserted.forEach { resolver.delete(it, null, null) } }
    }
}

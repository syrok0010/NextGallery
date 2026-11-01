package com.syrok0010.nextgallery.feature.upload

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import java.io.FileNotFoundException

/** Observe only available metadata; no hash/identity policy is introduced. Opening proves access. */
internal class UploadSource(private val resolver: ContentResolver) {
    fun snapshot(selection: UploadSelection): SourceStamp {
        val uri = Uri.parse(selection.contentUri)
        val columns =
            arrayOf(
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.GENERATION_MODIFIED,
            )
        val stamp = resolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw FileNotFoundException()
            fun field(index: Int) = if (cursor.isNull(index)) null else cursor.getLong(index)
            SourceStamp(field(0) ?: -1L, field(1), field(2))
        } ?: throw FileNotFoundException()
        resolver.openInputStream(uri)?.use { } ?: throw FileNotFoundException()
        return stamp
    }
}

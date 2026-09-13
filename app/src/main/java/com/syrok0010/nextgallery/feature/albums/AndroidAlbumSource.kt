package com.syrok0010.nextgallery.feature.albums

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.syrok0010.nextgallery.feature.timeline.local.AndroidMediaStoreChangeObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Folder catalog reads only MediaStore columns; it does not repeat EXIF/identity indexing. */
internal class AndroidAlbumSource(private val resolver: ContentResolver) : LocalAlbumSource {
    override fun changes() = AndroidMediaStoreChangeObserver(resolver).changes()
    override suspend fun load(): List<AlbumSummary> = withContext(Dispatchers.IO) {
        val columns = arrayOf("_id", "volume_name", "relative_path", "media_type", "date_modified")
        val selection = "media_type IN (?, ?) AND is_pending = 0 AND is_trashed = 0"
        val args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
        val context = currentCoroutineContext()
        resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), columns, selection, args,
            "date_modified DESC, _id DESC")?.use { cursor ->
            localAlbumSummaries(sequence {
                while (cursor.moveToNext()) {
                    context.ensureActive()
                    val volume = cursor.getString(1) ?: continue
                    val path = cursor.getString(2) ?: continue
                    val collection = if (cursor.getInt(3) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                        MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
                    yield(LocalAlbumEntry(volume, path, ContentUris.withAppendedId(collection, cursor.getLong(0)).toString(), cursor.getLong(4)))
                }
            })
        } ?: error("MediaStore returned no catalog cursor")
    }
}

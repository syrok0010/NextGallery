package com.syrok0010.nextgallery.feature.albums

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.network.bestEffort
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaProjectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import com.syrok0010.nextgallery.feature.timeline.local.canonicalTimelineSeconds

/** Reads only the selected folder, without running a second EXIF index. */
internal class AndroidAlbumContents(
    private val resolver: ContentResolver,
    private val identities: MediaIdentityRegistry,
    private val projectionStore: LocalMediaProjectionStore,
) {
    fun load(location: AlbumLocation.Folder) = flow {
        val cached = bestEffort { projectionStore.loadLocalMediaProjection() }
            .getOrDefault(emptyList())
            .associateBy { it.localCopy?.contentUri }
        val columns = arrayOf(
            "_id", "media_type", "_display_name", "mime_type", "width", "height",
            "datetaken", "date_modified", "date_added", "duration", "orientation",
        )
        val selection =
            "volume_name = ? AND relative_path = ? AND media_type IN (?, ?) AND is_pending = 0 AND is_trashed = 0"
        val args = arrayOf(
            location.volume,
            location.path,
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val items = mutableListOf<MediaItem>()
        val candidates = mutableListOf<MediaIdentityCandidate>()
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            columns,
            selection,
            args,
            "_id DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = cursor.getLong(0)
                val video = cursor.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val collection = if (video) MediaStore.Video.Media.getContentUri(location.volume)
                else MediaStore.Images.Media.getContentUri(location.volume)
                val uri = ContentUris.withAppendedId(collection, id).toString()
                val modified = cursor.getLong(7)
                val epoch = canonicalTimelineSeconds(null, cursor.getLong(6))
                    ?: modified.takeIf { it > 0 } ?: cursor.getLong(8)
                val day = Math.floorDiv(epoch, 86_400L).toInt()
                val rotated = cursor.getInt(10) in listOf(90, 270)
                val publishedId = MediaId.generate()
                // Use the same aggregate URI identity as the device index; bytes use the exact volume.
                val identityUri = ContentUris.withAppendedId(
                    if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id,
                ).toString()
                candidates += MediaIdentityCandidate(
                    MediaSourceIdentity(
                        MediaSourceKind.Local,
                        identityUri,
                    ),
                    publishedId, emptySet(),
                )
                items += MediaItem(
                    mediaId = publishedId,
                    dayId = day,
                    displayName = cursor.getString(2) ?: id.toString(),
                    mimeType = cursor.getString(3),
                    width = cursor.getInt(if (rotated) 5 else 4).takeIf { it > 0 },
                    height = cursor.getInt(if (rotated) 4 else 5).takeIf { it > 0 },
                    etag = null,
                    livePhotoId = null,
                    auid = null,
                    buid = null,
                    sharedBy = null,
                    takenAtEpochSeconds = epoch,
                    isVideo = video,
                    videoDurationSeconds = if (video) cursor.getLong(9) / 1000 else null,
                    isFavorite = false,
                    isHidden = false,
                    assetRef = MediaAssetRef.LocalContent(uri, modified),
                )
            }
        } ?: error("MediaStore returned no folder cursor")
        val resolved = identities.resolve(candidates).mediaIds
        val identified = items.mapIndexed { index, item ->
            val source = candidates[index].source
            val canonical = cached[source.sourceKey]?.takeIf {
                it.localCopy?.modifiedAtEpochSeconds == item.localCopy?.modifiedAtEpochSeconds
            }
            item.copy(
                mediaId = resolved.getValue(source),
                dayId = canonical?.dayId ?: item.dayId,
                takenAtEpochSeconds = canonical?.takenAtEpochSeconds ?: item.takenAtEpochSeconds,
                auid = canonical?.auid, buid = canonical?.buid,
            )
        }
        emit(
            AlbumContentsBatch(
                identified
                    .sortedByDescending { it.takenAtEpochSeconds }
                    .distinctBy { it.mediaId },
                items.size,
                items.size,
            ),
        )
    }.flowOn(Dispatchers.IO)
}

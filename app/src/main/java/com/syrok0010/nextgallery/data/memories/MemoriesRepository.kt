package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.ApiFactory
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

class MemoriesRepository(
    private val apiFactory: ApiFactory,
) {
    suspend fun loadInitialTimeline(credentials: AccountCredentials): TimelineSnapshot {
        val api = apiFactory.memoriesApi(credentials)
        val config = api.config()
        val days = api.days()
        val preloadedPhotos = days.flatMap { it.detail }
        val dayIdsToLoad = days
            .asSequence()
            .filter { it.count > 0 && it.detail.isEmpty() }
            .take(12)
            .map { it.dayid }
            .toList()

        val lazyPhotos = if (dayIdsToLoad.isEmpty()) {
            emptyList()
        } else {
            api.dayDetails(dayIdsToLoad.joinToString(","))
        }

        val photos = (preloadedPhotos + lazyPhotos)
            .distinctBy { it.fileid }
            .map { it.toMediaItem(credentials.serverUrl) }
            .sortedWith(compareByDescending<MediaItem> { it.takenAtEpochSeconds ?: 0L }.thenByDescending { it.fileId })

        return TimelineSnapshot(
            memoriesVersion = config.version,
            timelinePath = config.timelinePath,
            totalDayCount = days.size,
            totalMediaCountHint = days.sumOf { it.count },
            items = photos,
        )
    }

    private fun MemoriesPhotoDto.toMediaItem(serverUrl: String): MediaItem {
        val normalizedServerUrl = serverUrl.trimEnd('/')
        val isVideoValue = isVideo == 1 || mimetype?.startsWith("video/") == true

        return MediaItem(
            fileId = fileid,
            dayId = dayid,
            day = LocalDate.ofEpochDay(dayid.toLong()),
            displayName = basename ?: "file-$fileid",
            mimeType = mimetype,
            width = w,
            height = h,
            etag = etag,
            auid = auid,
            takenAtEpochSeconds = epoch,
            isVideo = isVideoValue,
            videoDurationSeconds = videoDuration,
            isFavorite = isFavorite.asFlexibleBoolean(),
            thumbnailUrl = "$normalizedServerUrl/apps/memories/api/image/preview/$fileid?x=512&y=512&a=1",
            detailPreviewUrl = "$normalizedServerUrl/apps/memories/api/image/preview/$fileid?x=1600&y=1600&a=1",
        )
    }

    private fun JsonElement?.asFlexibleBoolean(): Boolean {
        val primitive = this?.jsonPrimitive ?: return false
        return primitive.booleanOrNull
            ?: primitive.intOrNull?.let { it != 0 }
            ?: primitive.content.equals("true", ignoreCase = true)
            || primitive.content == "1"
    }
}

data class TimelineSnapshot(
    val memoriesVersion: String,
    val timelinePath: String?,
    val totalDayCount: Int,
    val totalMediaCountHint: Int,
    val items: List<MediaItem>,
)

data class MediaItem(
    val fileId: Long,
    val dayId: Int,
    val day: LocalDate,
    val displayName: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val etag: String?,
    val auid: String?,
    val takenAtEpochSeconds: Long?,
    val isVideo: Boolean,
    val videoDurationSeconds: Long?,
    val isFavorite: Boolean,
    val thumbnailUrl: String,
    val detailPreviewUrl: String,
)

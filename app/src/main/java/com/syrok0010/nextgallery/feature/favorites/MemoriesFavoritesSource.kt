package com.syrok0010.nextgallery.feature.favorites

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.library.CloudFavoriteMetadata
import com.syrok0010.nextgallery.feature.timeline.persistence.TimelineCacheRepository
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesDayDto
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesPhotoDto
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesRepository
import java.io.IOException
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

internal interface FavoritesApi {
    @GET("apps/memories/api/days")
    suspend fun days(@Query("fav") favorite: Int = 1): List<MemoriesDayDto>

    @GET("apps/memories/api/days/{ids}")
    suspend fun photos(
        @Path("ids") ids: String,
        @Query("fav") favorite: Int = 1,
    ): List<MemoriesPhotoDto>
}

/** Day index is server-filtered; every incomplete day is fetched, independently of the main viewport. */
internal suspend fun loadFavoritePhotos(api: FavoritesApi): List<MemoriesPhotoDto> {
    val days = api.days().distinctBy { it.dayid }
    val photos = days.flatMap { it.detail }.associateByTo(linkedMapOf()) { it.fileid }
    val incomplete = days.filter { day -> day.detail.distinctBy { it.fileid }.size < day.count }
    incomplete.map { it.dayid }.chunked(10).forEach { ids ->
        api.photos(ids.joinToString(",")).forEach { photos[it.fileid] = it }
    }
    // A changing server collection or truncated response must not be presented as a complete result.
    val counts = photos.values.groupingBy { it.dayid }.eachCount()
    if (days.any { day ->
            counts.getOrDefault(
                day.dayid,
                0,
            ) != day.count
        } ||
        counts.keys.any { id ->
            days.none {
                it.dayid == id
            }
        }
    ) {
        throw IOException("Favorites changed while loading; refresh required")
    }
    return photos.values.toList()
}

internal class MemoriesFavoritesSource(
    private val transport: NextcloudTransport,
    private val memories: MemoriesRepository,
    private val metadata: CloudFavoriteMetadata,
    private val cache: TimelineCacheRepository,
) {
    suspend fun load(credentials: AccountCredentials): List<MediaItem> {
        val read = metadata.begin(credentials)
        val api = transport
            .retrofit(
                transport.normalizeBaseUrl(credentials.serverUrl),
                transport.authenticatedClient(credentials),
            ).create(FavoritesApi::class.java)
        val photos = loadFavoritePhotos(api)
        if (!metadata.isCurrent(read)) throw kotlinx.coroutines.CancellationException()
        val items = with(memories) { photos.toIdentifiedMediaItems() }
        return metadata.ingest(read, items) { corrected ->
            cache.saveDayDetails(corrected, emptySet())
        }
    }
}

package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.http.GET

internal interface AlbumApi {
    @GET("apps/memories/api/config") suspend fun config(): JsonObject
    @GET("apps/memories/api/clusters/albums") suspend fun albums(): List<JsonObject>
}
internal class MemoriesAlbumSource(private val transport: NextcloudTransport) : RemoteAlbumSource {
    override suspend fun load(credentials: AccountCredentials): RemoteAlbums {
        val api = transport.retrofit(transport.normalizeBaseUrl(credentials.serverUrl), transport.authenticatedClient(credentials))
            .create(AlbumApi::class.java)
        val config = api.config()
        if (config.text("albums_enabled") !in setOf("true", "1")) return RemoteAlbums(emptyList(), supported = false)
        val showHidden = config.text("show_hidden_albums") in setOf("true", "1")
        return RemoteAlbums(api.albums().map(::remoteAlbumSummary)
            .filter { showHidden || !it.name.startsWith('.') }.distinctBy { it.id })
    }
}
private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content
private fun JsonObject.number(key: String): Long? = text(key)?.toLongOrNull()
internal fun remoteAlbumSummary(json: JsonObject): AlbumSummary {
    val id = requireNotNull(json.number("album_id")) { "Missing album id" }
    val name = requireNotNull(json.text("name")) { "Missing album name" }
    val coverId = json.number("cover")?.takeIf { it > 0 }
    val fileId = coverId ?: json.number("last_added_photo")?.takeIf { it > 0 }
    return AlbumSummary(
        id = "remote:$id",
        name = name,
        origin = AlbumOrigin.Nextcloud,
        count = json.number("count")?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt(),
        detail = json.text("user_display") ?: json.text("user").orEmpty(),
        location = (json.text("cluster_id") ?: json.text("user")?.let { "$it/$name" })
            ?.let(AlbumLocation::Remote),
        cover = fileId?.let { AlbumCover.Remote(it, json.text(if (coverId != null) "cover_etag" else "last_added_photo_etag")) },
    )
}

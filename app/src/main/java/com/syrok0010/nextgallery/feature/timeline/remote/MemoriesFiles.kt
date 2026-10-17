package com.syrok0010.nextgallery.feature.timeline.remote

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import retrofit2.http.GET
import retrofit2.http.Path

internal interface MemoriesFilesApi {
    @GET("ocs/v2.php/cloud/user?format=json")
    suspend fun currentUser(): JsonObject

    @GET("apps/memories/api/image/info/{id}")
    suspend fun info(@Path("id") fileId: Long): JsonObject
}

/** Location of a Memories file in the user's DAV files: [path] from the files root, [name]. */
internal data class RemoteFile(val path: String, val name: String)

/**
 * Bridges Memories file ids to Nextcloud DAV. Memories has no write API, so writes go through
 * DAV paths of the account's uid.
 */
internal class MemoriesFiles(private val transport: NextcloudTransport) {
    /** Login Flow may hand out an e-mail as login name; DAV paths use the uid. */
    suspend fun currentUser(credentials: AccountCredentials): String {
        val response = api(credentials).currentUser()
        val data = response["ocs"]?.jsonObject?.get("data")?.jsonObject
        return requireNotNull(data?.text("id")?.takeIf(String::isNotBlank)) {
            "Missing Nextcloud user id"
        }
    }

    suspend fun file(credentials: AccountCredentials, fileId: Long): RemoteFile {
        val info = api(credentials).info(fileId)
        return RemoteFile(
            path = requireNotNull(info.text("filename")) { "Missing path of file $fileId" },
            name = requireNotNull(info.text("basename")) { "Missing name of file $fileId" },
        )
    }

    private fun api(credentials: AccountCredentials) =
        transport
            .retrofit(
                transport.normalizeBaseUrl(credentials.serverUrl),
                transport.authenticatedClient(credentials),
            ).create(MemoriesFilesApi::class.java)
}

/** Memories and OCS send JSON null and missing fields alike; both read as null. */
internal fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content

package com.syrok0010.nextgallery.core.network.capabilities

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.OcsResponseDto
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.serialization.Serializable
import retrofit2.http.GET

/** Endpoint-level access to the authenticated OCS capabilities of a Nextcloud server. */
class NextcloudCapabilitiesClient(private val transport: NextcloudTransport) {
    suspend fun capabilities(credentials: AccountCredentials): NextcloudCapabilitiesDto =
        transport
            .retrofit(
                transport.normalizeBaseUrl(credentials.serverUrl),
                transport.authenticatedClient(credentials),
            ).create(NextcloudCapabilitiesApi::class.java)
            .capabilities()
            .ocs.data.capabilities
}

private interface NextcloudCapabilitiesApi {
    @GET("ocs/v2.php/cloud/capabilities?format=json")
    suspend fun capabilities(): OcsResponseDto<NextcloudCapabilitiesDataDto>
}

@Serializable
data class NextcloudCapabilitiesDataDto(val capabilities: NextcloudCapabilitiesDto)

/** Capabilities of disabled apps are absent rather than false. */
@Serializable
data class NextcloudCapabilitiesDto(val files: FilesCapabilitiesDto? = null)

/** `undelete` is published by files_trashbin. */
@Serializable
data class FilesCapabilitiesDto(val undelete: Boolean? = null)

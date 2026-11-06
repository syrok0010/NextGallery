package com.syrok0010.nextgallery.core.network

import kotlinx.serialization.Serializable

/**
 * OCS envelope. Clients call `ocs/v2.php`, where a failed OCS status arrives as a non-2xx HTTP
 * response, so `ocs.meta` is not needed to tell success from failure.
 */
@Serializable
data class OcsResponseDto<T>(val ocs: OcsBodyDto<T>)

@Serializable
data class OcsBodyDto<T>(val data: T)

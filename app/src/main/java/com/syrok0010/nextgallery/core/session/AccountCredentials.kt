package com.syrok0010.nextgallery.core.session

import kotlinx.serialization.Serializable

@Serializable
data class AccountCredentials(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
    /** Nextcloud uid for DAV paths; Login Flow may hand out an e-mail as [loginName]. */
    val userId: String,
    /** Durable login lifetime; a fresh Login Flow always creates a new token. */
    val ownerToken: String = java.util.UUID.randomUUID().toString(),
)

package com.syrok0010.nextgallery.data.credentials

import kotlinx.serialization.Serializable

@Serializable
data class AccountCredentials(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
)

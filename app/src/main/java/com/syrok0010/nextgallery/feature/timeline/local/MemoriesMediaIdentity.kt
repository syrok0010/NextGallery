package com.syrok0010.nextgallery.data.local

import java.math.BigInteger
import java.security.MessageDigest

data class MediaAliases(
    val auid: String,
    val buid: String,
)

object MemoriesMediaIdentity {
    fun calculate(
        baseName: String,
        sizeBytes: Long,
        dateTakenMillis: Long,
        imageUniqueId: String?,
    ): MediaAliases {
        val identityEpochSeconds = dateTakenMillis / 1_000
        val buidSuffix = imageUniqueId
            ?.let { "iuid=$it" }
            ?: "size=$sizeBytes"
        return MediaAliases(
            auid = md5("$identityEpochSeconds$sizeBytes"),
            buid = md5("$baseName$buidSuffix"),
        )
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }
}

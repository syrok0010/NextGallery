package com.syrok0010.nextgallery.domain.media

import java.util.UUID

@JvmInline
value class MediaId(val value: String) {
    init {
        require(value.isNotBlank()) { "MediaId must not be blank" }
    }

    companion object {
        fun generate(): MediaId = MediaId(UUID.randomUUID().toString())
    }
}

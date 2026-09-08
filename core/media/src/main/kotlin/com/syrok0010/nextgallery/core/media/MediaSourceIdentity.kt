package com.syrok0010.nextgallery.domain.media

enum class MediaSourceKind {
    Memories,
    Local,
}

data class MediaSourceIdentity(
    val source: MediaSourceKind,
    val sourceKey: String,
)

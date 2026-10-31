package com.syrok0010.nextgallery.feature.auth

sealed interface MemoriesCompatibility {
    data object Supported : MemoriesCompatibility
    data class TooOld(val version: String) : MemoriesCompatibility
    data object Unavailable : MemoriesCompatibility
    data object UnknownVersion : MemoriesCompatibility
    data object NetworkError : MemoriesCompatibility
    data object CheckFailed : MemoriesCompatibility
}

internal fun memoriesCompatibility(version: String?): MemoriesCompatibility {
    val value = version ?: return MemoriesCompatibility.UnknownVersion
    val major = value
        .takeIf {
            it.matches(
                Regex("[0-9]+(?:\\.[0-9]+)+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?"),
            )
        }?.substringBefore('.')
        ?.toIntOrNull()
        ?: return MemoriesCompatibility.UnknownVersion
    return if (major >= 10) {
        MemoriesCompatibility.Supported
    } else {
        MemoriesCompatibility.TooOld(value)
    }
}


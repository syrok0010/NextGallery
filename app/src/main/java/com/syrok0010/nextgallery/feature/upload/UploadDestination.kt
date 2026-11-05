package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Memories config contains a semicolon-separated list, not a single DAV directory. */
internal fun timelinePaths(value: String?): List<String> =
    value?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

internal fun uploadUrl(
    serverUrl: String,
    userId: String,
    destination: String,
    fileName: String,
): HttpUrl =
    NextcloudTransport
        .normalizeServerOrigin(serverUrl)
        .toHttpUrl()
        .newBuilder()
        .apply {
            addPathSegment("remote.php")
            addPathSegment("dav")
            addPathSegment("files")
            addPathSegment(userId)
            destination.split('/').filter(String::isNotEmpty).forEach(::addPathSegment)
        }.addPathSegment(fileName)
        .build()

package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.session.AccountCredentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

/**
 * The file [url] names in the files root of [account]: the inverse of [uploadUrl] for a pinned
 * destination. Null for a URL outside that root.
 */
internal fun uploadPath(account: AccountCredentials, url: String): DavFilePath? {
    val root = uploadUrl(account.serverUrl, account.userId, "", "-").pathSegments.dropLast(1)
    val segments = url.toHttpUrlOrNull()?.pathSegments ?: return null
    if (segments.size <= root.size || segments.subList(0, root.size) != root) return null
    return DavFilePath(account.userId, segments.drop(root.size))
}

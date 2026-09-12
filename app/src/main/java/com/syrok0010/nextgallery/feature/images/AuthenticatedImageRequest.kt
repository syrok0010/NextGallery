package com.syrok0010.nextgallery.feature.images

import android.content.Context
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials

internal fun authenticatedImageRequest(context: Context, url: String, credentials: AccountCredentials): ImageRequest =
    ImageRequest.Builder(context).data(url).httpHeaders(authenticatedNetworkHeaders(credentials)).build()

internal fun authenticatedNetworkHeaders(credentials: AccountCredentials): NetworkHeaders =
    NetworkHeaders.Builder()
        .set("Authorization", NextcloudTransport.authorizationHeader(credentials))
        .set("X-Requested-With", "XMLHttpRequest")
        .set("OCS-APIRequest", "true")
        .build()

package com.syrok0010.nextgallery.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import okhttp3.Credentials as OkHttpCredentials

@Composable
internal fun AuthenticatedImage(
    url: String,
    credentials: AccountCredentials,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val headers = NetworkHeaders.Builder()
        .set("Authorization", OkHttpCredentials.basic(credentials.loginName, credentials.appPassword))
        .set("X-Requested-With", "XMLHttpRequest")
        .set("OCS-APIRequest", "true")
        .build()
    val request = ImageRequest.Builder(context)
        .data(url)
        .httpHeaders(headers)
        .build()

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

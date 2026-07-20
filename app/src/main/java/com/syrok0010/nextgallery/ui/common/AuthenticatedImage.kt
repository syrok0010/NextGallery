package com.syrok0010.nextgallery.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import android.content.Context
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.NextcloudTransport

@Composable
internal fun AuthenticatedImage(
    url: String,
    credentials: AccountCredentials,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val request = remember(context, url, credentials) {
        authenticatedImageRequest(
            context = context,
            url = url,
            credentials = credentials,
        )
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
internal fun CachedImage(
    data: Any,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val request = remember(context, data) {
        ImageRequest.Builder(context)
            .data(data)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

internal fun authenticatedImageRequest(
    context: Context,
    url: String,
    credentials: AccountCredentials,
) = NextcloudTransport.authenticatedImageRequest(
    context = context,
    url = url,
    credentials = credentials,
)

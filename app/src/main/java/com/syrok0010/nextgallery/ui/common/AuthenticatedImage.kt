package com.syrok0010.nextgallery.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import android.content.Context
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.NextcloudTransport

@Composable
internal fun AuthenticatedImage(
    url: String,
    credentials: AccountCredentials,
    data: Any? = null,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val request = remember(context, url, credentials, data) {
        authenticatedImageRequest(
            context = context,
            url = url,
            credentials = credentials,
            data = data,
        )
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
    data: Any? = null,
) = NextcloudTransport.authenticatedImageRequest(
    context = context,
    url = url,
    credentials = credentials,
    data = data,
)

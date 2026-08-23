package com.syrok0010.nextgallery.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesAssetUrlFactory
import com.syrok0010.nextgallery.data.network.NextcloudTransport
import com.syrok0010.nextgallery.data.thumbnail.thumbnailRequest
import com.syrok0010.nextgallery.data.thumbnail.coilCacheKey

internal enum class MediaImagePurpose {
    TimelineThumbnail,
    DetailPreview,
    Original,
}

@Composable
internal fun MediaAssetImage(
    item: MediaItem,
    credentials: AccountCredentials,
    purpose: MediaImagePurpose,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val requests = remember(context, item, credentials, purpose) {
        mediaImageRequests(context, item, credentials, purpose)
    }
    requests.forEach { request ->
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

internal fun mediaImageRequest(
    context: Context,
    item: MediaItem,
    credentials: AccountCredentials,
    purpose: MediaImagePurpose,
): ImageRequest = mediaImageRequests(context, item, credentials, purpose).last()

internal fun mediaImageRequests(
    context: Context,
    item: MediaItem,
    credentials: AccountCredentials,
    purpose: MediaImagePurpose,
): List<ImageRequest> = when (val assetRef = item.assetRef) {
    is MediaAssetRef.MemoriesFile -> {
        val urls = MemoriesAssetUrlFactory.urlsFor(assetRef, credentials.serverUrl)
        when (purpose) {
            MediaImagePurpose.TimelineThumbnail -> listOf(
                ImageRequest.Builder(context)
                    .data(thumbnailRequest(credentials, assetRef.photoFileId, item.etag))
                    .build(),
            )
            MediaImagePurpose.DetailPreview -> listOf(
                ImageRequest.Builder(context)
                    .data(thumbnailRequest(credentials, assetRef.photoFileId, item.etag))
                    .build(),
                NextcloudTransport.authenticatedImageRequest(context, urls.detailPreviewUrl, credentials),
            )
            MediaImagePurpose.Original -> listOf(
                NextcloudTransport.authenticatedImageRequest(context, urls.originalUrl, credentials),
            )
        }
    }
    is MediaAssetRef.LocalContent -> {
        val cacheKey = assetRef.coilCacheKey()
        listOf(
            ImageRequest.Builder(context)
                .data(assetRef.contentUri)
                .memoryCacheKey("$cacheKey:$purpose")
                .diskCacheKey("$cacheKey:$purpose")
                .apply {
                    if (purpose != MediaImagePurpose.TimelineThumbnail) {
                        placeholderMemoryCacheKey(
                            "$cacheKey:${MediaImagePurpose.TimelineThumbnail}",
                        )
                    }
                }
                .build(),
        )
    }
}

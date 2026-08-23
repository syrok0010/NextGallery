package com.syrok0010.nextgallery.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.Image
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesAssetUrlFactory
import com.syrok0010.nextgallery.data.network.NextcloudTransport
import com.syrok0010.nextgallery.data.thumbnail.coilCacheKey
import com.syrok0010.nextgallery.data.thumbnail.thumbnailRequest

internal enum class MediaImagePurpose {
    TimelineThumbnail,
    DetailPreview,
    Original,
}

internal data class MediaImageRequestPlan(
    val primary: ImageRequest,
    val fallback: ImageRequest? = null,
    val preview: ImageRequest? = null,
)

@Composable
internal fun MediaAssetImage(
    item: MediaItem,
    credentials: AccountCredentials,
    purpose: MediaImagePurpose,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    imageLoader: ImageLoader = SingletonImageLoader.get(LocalContext.current),
) {
    val context = LocalContext.current
    val plan = remember(context, item, credentials, purpose) {
        mediaImageRequestPlan(context, item, credentials, purpose)
    }
    val request = rememberFallbackImageRequest(context, plan)

    Box(modifier = modifier) {
        plan.preview?.let { preview ->
            AsyncImage(
                model = preview,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                imageLoader = imageLoader,
            )
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            imageLoader = imageLoader,
        )
    }
}

@Composable
internal fun rememberFallbackImageRequest(
    context: Context,
    plan: MediaImageRequestPlan,
    onSuccess: (Image) -> Unit = {},
    onError: () -> Unit = {},
): ImageRequest {
    var useFallback by remember(plan) { mutableStateOf(false) }
    val currentOnSuccess by rememberUpdatedState(onSuccess)
    val currentOnError by rememberUpdatedState(onError)
    val activeRequest = if (useFallback) plan.fallback ?: plan.primary else plan.primary
    return remember(activeRequest, plan.fallback) {
        activeRequest.newBuilder(context)
            .listener(
                onSuccess = { _, result -> currentOnSuccess(result.image) },
                onError = { _, _ ->
                    currentOnError()
                    if (!useFallback && plan.fallback != null) useFallback = true
                },
            )
            .build()
    }
}

internal fun mediaImageRequestPlan(
    context: Context,
    item: MediaItem,
    credentials: AccountCredentials,
    purpose: MediaImagePurpose,
): MediaImageRequestPlan = when (val assetRef = item.assetRef) {
    is MediaAssetRef.MemoriesFile -> remoteRequestPlan(context, item, assetRef, credentials, purpose)
    is MediaAssetRef.LocalContent -> MediaImageRequestPlan(
        primary = localRequest(context, assetRef, purpose),
    )
    is MediaAssetRef.LocalFirst -> {
        val remote = remoteRequestPlan(context, item, assetRef.remote, credentials, purpose)
        MediaImageRequestPlan(
            primary = localRequest(context, assetRef.local, purpose),
            fallback = remote.primary,
        )
    }
}

private fun remoteRequestPlan(
    context: Context,
    item: MediaItem,
    assetRef: MediaAssetRef.MemoriesFile,
    credentials: AccountCredentials,
    purpose: MediaImagePurpose,
): MediaImageRequestPlan {
    val urls = MemoriesAssetUrlFactory.urlsFor(assetRef, credentials.serverUrl)
    return when (purpose) {
        MediaImagePurpose.TimelineThumbnail -> MediaImageRequestPlan(
            primary = ImageRequest.Builder(context)
                .data(thumbnailRequest(credentials, assetRef.photoFileId, item.etag))
                .build(),
        )
        MediaImagePurpose.DetailPreview -> MediaImageRequestPlan(
            primary = NextcloudTransport.authenticatedImageRequest(context, urls.detailPreviewUrl, credentials),
            preview = ImageRequest.Builder(context)
                .data(thumbnailRequest(credentials, assetRef.photoFileId, item.etag))
                .build(),
        )
        MediaImagePurpose.Original -> MediaImageRequestPlan(
            primary = NextcloudTransport.authenticatedImageRequest(context, urls.originalUrl, credentials),
        )
    }
}

private fun localRequest(
    context: Context,
    assetRef: MediaAssetRef.LocalContent,
    purpose: MediaImagePurpose,
): ImageRequest {
    val cacheKey = assetRef.coilCacheKey()
    return ImageRequest.Builder(context)
        .data(assetRef.contentUri)
        .memoryCacheKey("$cacheKey:$purpose")
        .diskCacheKey("$cacheKey:$purpose")
        .apply {
            if (purpose != MediaImagePurpose.TimelineThumbnail) {
                placeholderMemoryCacheKey("$cacheKey:${MediaImagePurpose.TimelineThumbnail}")
            }
        }
        .build()
}

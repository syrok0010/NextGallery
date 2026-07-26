package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.TimelineSlot
import com.syrok0010.nextgallery.data.thumbnail.thumbnailRequest
import com.syrok0010.nextgallery.ui.common.ThumbnailImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TimelineDayHeader(dayId: Int) {
    val pattern = stringResource(R.string.timeline_day_header_pattern)
    val formatter = remember(pattern) {
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    }
    val title = remember(dayId, formatter) {
        LocalDate.ofEpochDay(dayId.toLong()).format(formatter)
    }

    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
internal fun TimelineSlotTile(
    slot: TimelineSlot,
    credentials: AccountCredentials,
    registerTimelineTile: (fileId: Long, boundsProvider: () -> Rect?) -> () -> Unit,
    onSelect: (MediaItem) -> Unit,
) {
    val item = slot.mediaItem
    val cloudCopyDescription = stringResource(R.string.media_cloud_copy)

    if (item == null) {
        PlaceholderMediaTile(cloudCopyDescription = cloudCopyDescription)
    } else {
        MediaTile(
            item = item,
            credentials = credentials,
            cloudCopyDescription = cloudCopyDescription,
            registerTimelineTile = registerTimelineTile,
            onClick = { onSelect(item) },
        )
    }
}

@Composable
private fun PlaceholderMediaTile(cloudCopyDescription: String) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                stateDescription = cloudCopyDescription
            },
    ) {
        RemoteCloudIndicator(modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun MediaTile(
    item: MediaItem,
    credentials: AccountCredentials,
    cloudCopyDescription: String,
    registerTimelineTile: (fileId: Long, boundsProvider: () -> Rect?) -> () -> Unit,
    onClick: () -> Unit,
) {
    val coordinatesHolder = remember(item.fileId) {
        TimelineTileCoordinates()
    }
    DisposableEffect(item.fileId, registerTimelineTile) {
        val unregister = registerTimelineTile(item.fileId, coordinatesHolder::boundsInRoot)
        onDispose(unregister)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .onGloballyPositioned { coordinates ->
                coordinatesHolder.coordinates = coordinates
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                stateDescription = cloudCopyDescription
            }
            .clickable(onClick = onClick),
    ) {
        ThumbnailImage(
            request = remember(credentials, item.fileId, item.etag) {
                thumbnailRequest(
                    credentials = credentials,
                    fileId = item.fileId,
                    etag = item.etag,
                )
            },
            contentDescription = item.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        RemoteCloudIndicator(modifier = Modifier.align(Alignment.TopEnd))

        if (item.isVideo) {
            Text(
                text = stringResource(R.string.media_video_badge),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun RemoteCloudIndicator(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_cloud),
        contentDescription = null,
        modifier = modifier
            .padding(6.dp)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                shape = MaterialTheme.shapes.extraSmall,
            )
            .padding(4.dp)
            .size(16.dp)
            .testTag("remote-cloud-indicator"),
        tint = MaterialTheme.colorScheme.onSurface,
    )
}

private class TimelineTileCoordinates {
    var coordinates: LayoutCoordinates? = null

    fun boundsInRoot(): Rect? {
        return coordinates
            ?.takeIf(LayoutCoordinates::isAttached)
            ?.boundsInRoot()
    }
}

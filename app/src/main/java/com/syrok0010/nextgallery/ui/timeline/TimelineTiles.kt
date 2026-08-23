package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
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
import com.syrok0010.nextgallery.data.memories.hasRemoteCopy
import com.syrok0010.nextgallery.data.memories.TimelineSlot
import com.syrok0010.nextgallery.domain.media.MediaId
import com.syrok0010.nextgallery.ui.common.MediaAssetImage
import com.syrok0010.nextgallery.ui.common.MediaImagePurpose
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
    registerTimelineTile: (mediaId: MediaId, boundsProvider: () -> Rect?) -> () -> Unit,
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
            .cloudCopySemantics(cloudCopyDescription),
    ) {
        RemoteCloudIndicator(modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun MediaTile(
    item: MediaItem,
    credentials: AccountCredentials,
    cloudCopyDescription: String,
    registerTimelineTile: (mediaId: MediaId, boundsProvider: () -> Rect?) -> () -> Unit,
    onClick: () -> Unit,
) {
    val coordinatesHolder = remember(item.mediaId) {
        TimelineTileCoordinates()
    }
    DisposableEffect(item.mediaId, registerTimelineTile) {
        val unregister = registerTimelineTile(item.mediaId, coordinatesHolder::boundsInRoot)
        onDispose(unregister)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .onGloballyPositioned { coordinates ->
                coordinatesHolder.coordinates = coordinates
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (item.hasRemoteCopy) Modifier.cloudCopySemantics(cloudCopyDescription) else Modifier)
            .clickable(onClick = onClick),
    ) {
        MediaAssetImage(
            item = item,
            credentials = credentials,
            purpose = MediaImagePurpose.TimelineThumbnail,
            contentDescription = item.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        if (item.hasRemoteCopy) {
            RemoteCloudIndicator(modifier = Modifier.align(Alignment.TopEnd))
        }

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
    Box(
        modifier = modifier
            .padding(5.dp)
            .size(16.dp)
            .testTag("remote-cloud-indicator"),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_cloud),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 0.5.dp)
                .size(14.dp),
            tint = Color.Black.copy(alpha = 0.58f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_cloud),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(12.dp),
            tint = Color.White.copy(alpha = 0.92f),
        )
    }
}

private fun Modifier.cloudCopySemantics(description: String): Modifier =
    semantics {
        stateDescription = description
    }

private class TimelineTileCoordinates {
    var coordinates: LayoutCoordinates? = null

    fun boundsInRoot(): Rect? {
        return coordinates
            ?.takeIf(LayoutCoordinates::isAttached)
            ?.boundsInRoot()
    }
}

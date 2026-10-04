package com.syrok0010.nextgallery.feature.collection

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasLocalCopy
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import com.syrok0010.nextgallery.feature.images.MediaAssetImage
import com.syrok0010.nextgallery.feature.images.MediaImagePurpose
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.koin.compose.koinInject

@Composable
internal fun MediaDayHeader(dayId: Int) {
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
internal fun MediaSlotTile(
    slot: MediaSlot,
    registerTimelineTile: (mediaId: MediaId, boundsProvider: () -> Rect?) -> () -> Unit,
    onSelect: (MediaItem) -> Unit,
    selectionEnabled: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelectionModeChanged: (Boolean) -> Unit = {},
    onSelectionChanged: (MediaItem, Boolean) -> Unit = { _, _ -> },
    requestFactory: MediaImageRequestFactory = koinInject(),
) {
    val item = slot.mediaItem
    val cloudCopyDescription = stringResource(R.string.media_cloud_copy)
    val localCopyDescription = stringResource(R.string.media_local_copy)

    if (item == null) {
        PlaceholderMediaTile(cloudCopyDescription = cloudCopyDescription)
    } else {
        MediaTile(
            item = item,
            cloudCopyDescription = cloudCopyDescription,
            localCopyDescription = localCopyDescription,
            requestFactory = requestFactory,
            registerTimelineTile = registerTimelineTile,
            selectionEnabled = selectionEnabled,
            selectionMode = selectionMode,
            selected = selected,
            onClick = {
                if (selectionMode) {
                    onSelectionChanged(item, !selected)
                } else {
                    onSelect(item)
                }
            },
            onLongClick = {
                onSelectionModeChanged(true)
                onSelectionChanged(item, true)
            },
        )
    }
}

@Composable
private fun PlaceholderMediaTile(cloudCopyDescription: String) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .copyAvailabilitySemantics(cloudCopyDescription),
    ) {
        RemoteCloudIndicator(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp),
        )
    }
}

@Composable
private fun MediaTile(
    item: MediaItem,
    cloudCopyDescription: String,
    localCopyDescription: String,
    requestFactory: MediaImageRequestFactory,
    registerTimelineTile: (mediaId: MediaId, boundsProvider: () -> Rect?) -> () -> Unit,
    selectionEnabled: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean,
) {
    val coordinatesHolder = remember(item.mediaId) {
        TimelineTileCoordinates()
    }
    DisposableEffect(item.mediaId, registerTimelineTile) {
        val unregister = registerTimelineTile(item.mediaId, coordinatesHolder::boundsInRoot)
        onDispose(unregister)
    }
    val copyDescription = buildList {
        if (item.hasLocalCopy) add(localCopyDescription)
        if (item.hasRemoteCopy) add(cloudCopyDescription)
    }.joinToString()

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .onGloballyPositioned { coordinates ->
                coordinatesHolder.coordinates = coordinates
            }.background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (copyDescription.isNotEmpty()) {
                    Modifier.copyAvailabilitySemantics(
                        copyDescription,
                    )
                } else {
                    Modifier
                },
            ).combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick.takeIf { selectionEnabled },
            ),
    ) {
        MediaAssetImage(
            item = item,
            purpose = MediaImagePurpose.TimelineThumbnail,
            contentDescription = item.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            requestFactory = requestFactory,
        )

        if (item.hasLocalCopy || item.hasRemoteCopy) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (item.hasLocalCopy) {
                    LocalDeviceIndicator()
                }
                if (item.hasRemoteCopy) {
                    RemoteCloudIndicator()
                }
            }
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            ) {
                Text(
                    text = "✓",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
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
                    ).padding(horizontal = 6.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun RemoteCloudIndicator(modifier: Modifier = Modifier) {
    MediaCopyIndicator(
        iconRes = R.drawable.ic_cloud,
        testTag = "remote-cloud-indicator",
        modifier = modifier,
        foregroundOffsetY = 0.5.dp,
    )
}

@Composable
private fun LocalDeviceIndicator(modifier: Modifier = Modifier) {
    MediaCopyIndicator(
        iconRes = R.drawable.ic_phone,
        testTag = "local-device-indicator",
        modifier = modifier,
    )
}

@Composable
private fun MediaCopyIndicator(
    @DrawableRes iconRes: Int,
    testTag: String,
    modifier: Modifier = Modifier,
    foregroundOffsetY: Dp = 0.dp,
) {
    Box(
        modifier = modifier
            .size(16.dp)
            .testTag(testTag),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = foregroundOffsetY)
                .size(14.dp),
            tint = Color.Black.copy(alpha = 0.58f),
        )
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(12.dp),
            tint = Color.White.copy(alpha = 0.92f),
        )
    }
}

private fun Modifier.copyAvailabilitySemantics(description: String): Modifier =
    semantics {
        stateDescription = description
    }

private class TimelineTileCoordinates {
    var coordinates: LayoutCoordinates? = null

    fun boundsInRoot(): Rect? =
        coordinates
            ?.takeIf(LayoutCoordinates::isAttached)
            ?.boundsInRoot()
}

package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.ThumbnailPreview
import com.syrok0010.nextgallery.data.memories.TimelineSlot
import com.syrok0010.nextgallery.ui.common.CachedImage
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
    thumbnailPreview: ThumbnailPreview?,
    onBoundsChanged: (fileId: Long, bounds: Rect?) -> Unit,
    onSelect: (MediaItem) -> Unit,
) {
    val item = slot.mediaItem

    if (item == null) {
        PlaceholderMediaTile()
    } else {
        MediaTile(
            item = item,
            thumbnailPreview = thumbnailPreview,
            onBoundsChanged = onBoundsChanged,
            onClick = { onSelect(item) },
        )
    }
}

@Composable
private fun PlaceholderMediaTile() {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun MediaTile(
    item: MediaItem,
    thumbnailPreview: ThumbnailPreview?,
    onBoundsChanged: (fileId: Long, bounds: Rect?) -> Unit,
    onClick: () -> Unit,
) {
    DisposableEffect(item.fileId) {
        onDispose {
            onBoundsChanged(item.fileId, null)
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .onGloballyPositioned { coordinates ->
                onBoundsChanged(item.fileId, coordinates.boundsInRoot())
            }
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        if (thumbnailPreview != null) {
            CachedImage(
                data = thumbnailPreview.bytes,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
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

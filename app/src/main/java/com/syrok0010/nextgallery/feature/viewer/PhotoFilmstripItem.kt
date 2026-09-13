package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.images.MediaAssetImage
import com.syrok0010.nextgallery.feature.images.MediaImagePurpose

@Composable
internal fun PhotoFilmstripItem(
    item: MediaItem,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileWidth by animateDpAsState(width, tween(250), label = "filmstrip_tile_width")
    val tileHeight by animateDpAsState(height, label = "filmstrip_tile_height")
    MediaAssetImage(
        item = item,
        purpose = MediaImagePurpose.TimelineThumbnail,
        contentDescription = item.displayName,
        modifier = modifier.size(tileWidth, tileHeight).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick),
        contentScale = ContentScale.Crop,
    )
}

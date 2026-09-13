package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.images.MediaAssetImage
import com.syrok0010.nextgallery.feature.images.MediaImagePurpose
import com.syrok0010.nextgallery.feature.viewer.playback.VideoSources
import org.koin.compose.koinInject

@Composable
internal fun VideoFilmstripItem(
    item: MediaItem,
    placement: VideoFilmstripPlacement,
    playback: FilmstripPlayback,
    onClick: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    frameProvider: VideoFrameProvider? = null,
) {
    val factory: VideoFramesFactory? = if (frameProvider == null) koinInject() else null
    val scope = rememberCoroutineScope()
    val state = remember(item.mediaId, item.assetRef, factory, frameProvider, playback) {
        val sources = factory?.sources(item.assetRef) ?: VideoSources.from(item.assetRef)
        val provider = frameProvider ?: checkNotNull(factory).create(sources)
        VideoFilmstripItemState(item, provider, playback, scope, sources)
    }
    val activeSource = playback.sourceFor(item.mediaId)
    LaunchedEffect(state, placement, activeSource) { state.update(placement) }
    DisposableEffect(state) { onDispose { state.close() } }
    val isExpanded = placement.expanded
    val compactWidth = if (placement.activeWidth) FilmstripActiveTileWidth else FilmstripTileWidth
    val frameCount = state.frames.positionsMillis.size.takeIf { it > 0 }
        ?: videoFilmstripFrameCount((item.videoDurationSeconds ?: 0L) * 1000)
    val expandedWidth = VideoFilmstripFrameWidth * frameCount
    val height = if (placement.selected) FilmstripActiveTileHeight else FilmstripTileHeight
    val tileWidth by animateDpAsState(
        if (isExpanded) expandedWidth else compactWidth, tween(250), label = "filmstrip_tile_width",
    )
    val tileHeight by animateDpAsState(height, label = "filmstrip_tile_height")
    Box(
        modifier
            .size(tileWidth, tileHeight)
            .clip(RoundedCornerShape(4.dp))
            .clickable(
                onClickLabel = if (isExpanded) stringResource(R.string.video_filmstrip_collapse) else null,
                onClick = onClick,
            ),
    ) {
        Crossfade(
            isExpanded,
            animationSpec = tween(250),
            label = "video_card_expansion"
        ) { showFrames ->
            if (showFrames) {
                VideoFilmstripCard(
                    item = item,
                    state = state.frames,
                    fraction = placement.fraction,
                    onSeek = { state.seek(it, onSeekFraction) },
                    onRetry = state::retry,
                    modifier = Modifier.size(tileWidth, tileHeight),
                )
            } else {
                MediaAssetImage(
                    item = item,
                    purpose = MediaImagePurpose.TimelineThumbnail,
                    contentDescription = item.displayName,
                    modifier = Modifier.size(tileWidth, tileHeight),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

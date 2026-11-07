package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasLocalCopy
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import java.time.format.DateTimeFormatter

/** Height of the labeled action row under the filmstrip, without the navigation bar. */
internal val ViewerActionBarHeight = 64.dp

@Composable
internal fun ViewerChrome(
    item: MediaItem,
    visible: Boolean = true,
    playback: VideoPlaybackController? = null,
    onBack: () -> Unit,
    filmstrip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Labeled actions under the filmstrip; null keeps the filmstrip at the bottom edge. */
    actions: (@Composable () -> Unit)? = null,
    status: @Composable (visible: Boolean) -> Unit = {},
    /** 0..1 while the details sheet opens: the filmstrip and actions fade out under it. */
    detailsProgress: () -> Float = { 0f },
    onHeaderBottomChange: (Float) -> Unit = {},
) {
    Box(modifier = modifier) {
        playback?.let {
            VideoPlaybackChrome(
                it,
                controlsVisible = visible,
                bottomInset = if (actions != null) ViewerActionBarHeight else 0.dp,
            )
        }
        AnimatedVisibility(
            visible = visible && playback?.state?.isFullscreen != true,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = (1f - detailsProgress() * 1.6f).coerceIn(0f, 1f) },
                ) {
                    filmstrip()
                    if (actions != null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.48f))
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .testTag(ViewerActionsTestTag),
                        ) { actions() }
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .onGloballyPositioned { onHeaderBottomChange(it.size.height.toFloat()) }
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                            ),
                        ).windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = Color.White,
                            )
                        }
                        ViewerTitle(item, Modifier.weight(1f))
                        // Mirrors the back button, so the title stays centred on the screen.
                        Spacer(Modifier.width(ViewerHeaderSideWidth))
                    }
                    status(visible && playback?.state?.isFullscreen != true)
                }
            }
        }
    }
}

@Composable
private fun ViewerTitle(item: MediaItem, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.displayName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            CopyIndicators(item)
        }
        Text(
            text = item.day.format(DateTimeFormatter.ISO_LOCAL_DATE),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Where the object lives, with the same icons as the copy badges on the grid tiles. */
@Composable
private fun CopyIndicators(item: MediaItem) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.hasLocalCopy) {
            CopyIcon(
                R.drawable.ic_phone,
                stringResource(R.string.viewer_copy_phone),
            )
        }
        if (item.hasRemoteCopy) {
            CopyIcon(
                R.drawable.ic_cloud,
                stringResource(R.string.viewer_copy_cloud),
            )
        }
    }
}

@Composable
private fun CopyIcon(icon: Int, description: String, size: Dp = 14.dp) {
    Icon(
        painter = painterResource(icon),
        contentDescription = description,
        tint = Color.White.copy(alpha = 0.72f),
        modifier = Modifier.size(size),
    )
}

internal const val ViewerActionsTestTag = "viewer_actions"
private val ViewerHeaderSideWidth = 48.dp

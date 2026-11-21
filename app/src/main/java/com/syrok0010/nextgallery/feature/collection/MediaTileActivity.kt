package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R

/** Operations that change the copies of an object while its tile is on screen. */
internal enum class MediaTileOperation { Upload, AddToAlbum, RemoveFromAlbum, Delete }

/** [progress] is in 0..1, null when the operation cannot tell it for a single object. */
internal data class MediaTileActivity(
    val operation: MediaTileOperation,
    val progress: Float? = null,
)

/**
 * Takes the place of the copy indicators while an operation runs: those are what the operation
 * changes. The icon tells the operation, the ring its progress.
 */
@Composable
internal fun MediaTileActivityIndicator(
    activity: MediaTileActivity,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(24.dp)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            .testTag("tile-activity:${activity.operation}"),
    ) {
        val ring = Modifier.size(24.dp)
        val color = Color.White
        val track = Color.White.copy(alpha = 0.3f)
        if (activity.progress == null) {
            CircularProgressIndicator(ring, color = color, strokeWidth = 2.dp, trackColor = track)
        } else {
            CircularProgressIndicator(
                progress = { activity.progress },
                modifier = ring,
                color = color,
                strokeWidth = 2.dp,
                trackColor = track,
                gapSize = 0.dp,
            )
        }
        Icon(
            painter = activity.operation.icon(),
            contentDescription = null,
            modifier = Modifier.align(Alignment.Center).size(12.dp),
            tint = color,
        )
    }
}

@Composable
internal fun MediaTileOperation.icon(): Painter =
    when (this) {
        MediaTileOperation.Upload -> painterResource(R.drawable.ic_cloud)
        MediaTileOperation.AddToAlbum -> painterResource(R.drawable.ic_albums)
        MediaTileOperation.RemoveFromAlbum -> painterResource(R.drawable.ic_album_remove)
        MediaTileOperation.Delete -> rememberVectorPainter(Icons.Default.Delete)
    }

package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials

interface RemoteTimelineSource {
    suspend fun loadCachedTimeline(credentials: AccountCredentials): TimelineSnapshot?
    suspend fun loadInitialTimeline(credentials: AccountCredentials): TimelineSnapshot
    suspend fun loadTimelineDays(credentials: AccountCredentials, dayIds: List<Int>): List<MediaItem>
}

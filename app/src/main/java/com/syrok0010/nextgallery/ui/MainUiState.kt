package com.syrok0010.nextgallery.ui

import com.syrok0010.nextgallery.data.auth.LoginSession
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.ThumbnailPreview
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot

sealed interface SessionUiState {
    data object SignedOut : SessionUiState
    data class SignedIn(val credentials: AccountCredentials) : SessionUiState
}

data class LoginUiState(
    val serverUrlInput: String = "",
    val session: LoginSession? = null,
    val browserOpened: Boolean = false,
    val isPolling: Boolean = false,
)

data class TimelineUiState(
    val snapshot: TimelineSnapshot? = null,
    val loadingDayIds: Set<Int> = emptySet(),
    val failedDayIds: Set<Int> = emptySet(),
    val loadMoreError: UiText? = null,
    val thumbnailPreviews: Map<Long, ThumbnailPreview> = emptyMap(),
    val thumbnailLoadingFileIds: Set<Long> = emptySet(),
    val thumbnailFailedFileIds: Set<Long> = emptySet(),
)

internal fun TimelineUiState.withRefreshedSnapshot(refreshedSnapshot: TimelineSnapshot): TimelineUiState {
    val previousItemsByFileId = snapshot?.items?.associateBy { it.fileId }.orEmpty()
    val reusableThumbnailFileIds = refreshedSnapshot.items
        .asSequence()
        .filter { refreshedItem ->
            previousItemsByFileId[refreshedItem.fileId]?.etag == refreshedItem.etag
        }
        .mapTo(mutableSetOf()) { it.fileId }
    val refreshedDayIds = refreshedSnapshot.days.mapTo(mutableSetOf()) { it.dayId }

    return copy(
        snapshot = refreshedSnapshot,
        loadingDayIds = loadingDayIds
            .intersect(refreshedDayIds)
            .minus(refreshedSnapshot.loadedDayIds),
        failedDayIds = failedDayIds
            .intersect(refreshedDayIds)
            .minus(refreshedSnapshot.loadedDayIds),
        loadMoreError = null,
        thumbnailPreviews = thumbnailPreviews.filterKeys(reusableThumbnailFileIds::contains),
        thumbnailLoadingFileIds = thumbnailLoadingFileIds.intersect(reusableThumbnailFileIds),
        thumbnailFailedFileIds = emptySet(),
    )
}

data class AppMessageUiState(
    val status: UiText? = null,
    val error: UiText? = null,
)

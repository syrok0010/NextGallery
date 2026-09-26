package com.syrok0010.nextgallery.app.library

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.timeline.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Adapts execution publications to the photo screen. */
internal class TimelineProjectionStore(
    publication: LibraryPublicationStore,
    scope: CoroutineScope,
) {
    val state = publication.state
        .map { publication ->
            val source = publication.remote
            TimelineUiState(
                TimelineSnapshotProjection.project(source.snapshot, publication.library),
                source.loadingDayIds,
                source.failedDayIds,
                if (source.failedDayIds.isEmpty()) {
                    null
                } else {
                    uiText(
                        R.string.error_load_timeline_batch_failed,
                    )
                },
            )
        }.stateIn(scope, SharingStarted.Eagerly, TimelineUiState())
}

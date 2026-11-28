package com.syrok0010.nextgallery.app.library

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.library.CanonicalMediaLibrary
import com.syrok0010.nextgallery.feature.timeline.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Adapts execution publications to the photo screen. */
internal class TimelineProjectionStore(
    publication: LibraryPublicationStore,
    scope: CoroutineScope,
) {
    private data class ProjectionInput(
        val snapshot: TimelineSnapshot?,
        val library: CanonicalMediaLibrary,
    )

    private data class ProjectedTimeline(
        val input: ProjectionInput,
        val timeline: TimelineSnapshot?,
    )

    private val projected = publication.state
        .map { ProjectionInput(it.remote.snapshot, it.library) }
        .distinctUntilChanged { a, b -> a.snapshot === b.snapshot && a.library === b.library }
        .map { input ->
            ProjectedTimeline(
                input,
                TimelineSnapshotProjection.project(input.snapshot, input.library),
            )
        }

    val state = combine(publication.state, projected) { current, projected ->
        if (current.remote.snapshot !== projected.input.snapshot ||
            current.library !== projected.input.library
        ) {
            return@combine null
        }
        val failed = current.remote.failedDayIds
        TimelineUiState(
            projected.timeline,
            current.remote.loadingDayIds,
            failed,
            failed.takeIf { it.isNotEmpty() }?.let {
                uiText(R.string.error_load_timeline_batch_failed)
            },
        )
    }.filterNotNull()
        .stateIn(scope, SharingStarted.Eagerly, TimelineUiState())
}

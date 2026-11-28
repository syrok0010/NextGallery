package com.syrok0010.nextgallery.app.library

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.uiText
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
    private val projected = publication.state
        .map { it.remote.snapshot to it.library }
        .distinctUntilChanged { a, b -> a.first === b.first && a.second === b.second }
        .map { (snapshot, library) ->
            Triple(snapshot, library, TimelineSnapshotProjection.project(snapshot, library))
        }

    val state = combine(publication.state, projected) { current, (snapshot, library, timeline) ->
        if (current.remote.snapshot !== snapshot || current.library !== library) return@combine null
        val failed = current.remote.failedDayIds
        TimelineUiState(
            timeline,
            current.remote.loadingDayIds,
            failed,
            failed.takeIf { it.isNotEmpty() }?.let {
                uiText(R.string.error_load_timeline_batch_failed)
            },
        )
    }.filterNotNull()
        .stateIn(scope, SharingStarted.Eagerly, TimelineUiState())
}

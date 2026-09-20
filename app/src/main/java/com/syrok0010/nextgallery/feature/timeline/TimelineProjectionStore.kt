package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.uiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class TimelineProjectionStore {
    private val mutableState = MutableStateFlow(TimelineScreenState())
    val state = mutableState.asStateFlow()

    internal fun publish(source: TimelineWorkflowState, snapshot: TimelineSnapshot?) {
        val failed = source.failedDayIds
        mutableState.value = TimelineScreenState(
            timeline = TimelineUiState(
                snapshot,
                source.loadingDayIds,
                failed,
                if (failed.isEmpty()) null else uiText(R.string.error_load_timeline_batch_failed),
            ),
        )
    }

    internal fun clear() {
        mutableState.value = TimelineScreenState()
    }
}

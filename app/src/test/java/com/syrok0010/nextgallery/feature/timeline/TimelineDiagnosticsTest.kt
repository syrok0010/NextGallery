package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.UiText
import org.junit.Assert.*
import org.junit.Test

class TimelineDiagnosticsTest {
    @Test fun `cloud loading cannot hide indexing progress or last completed scan`() {
        val workflow = TimelineWorkflowState(
            remote = TimelineOperation.Loading,
            local = TimelineOperation.Indexing(25, 100),
            lastLocalProgress = TimelineOperation.Indexing(25, 100),
        )
        val messages = workflow.toUiState().sourceDiagnostics.filterIsInstance<UiText.Resource>()
        assertTrue(messages.any { it.id == R.string.status_loading_memories_api })
        assertEquals(
            listOf(25, 100),
            messages
                .single {
                    it.id == R.string.status_indexing_local_media
                }.args,
        )
        val finished = workflow
            .copy(
                local = TimelineOperation.Idle,
                lastLocalProgress = TimelineOperation.Indexing(100, 100),
            ).toUiState()
        assertEquals(
            listOf(100, 100),
            finished.sourceDiagnostics
                .filterIsInstance<UiText.Resource>()
                .single { it.id == R.string.diagnostics_local_processed }
                .args,
        )
    }

    @Test fun `both source errors survive simultaneous failure`() {
        val result = TimelineWorkflowState(
            remote = TimelineOperation.Failed,
            local = TimelineOperation.Failed,
        ).toUiState()
        val ids = result.sourceDiagnostics.filterIsInstance<UiText.Resource>().map { it.id }
        assertTrue(R.string.error_load_memories_api_failed in ids)
        assertTrue(R.string.error_load_local_media_failed in ids)
    }
}

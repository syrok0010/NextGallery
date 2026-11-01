package com.syrok0010.nextgallery.feature.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.syrok0010.nextgallery.core.media.MediaId

/**
 * Selection of one collection screen. The mode stays [active] until [clear], even with nothing
 * selected: leaving it is an explicit user action.
 */
@Stable
internal class MediaSelection(active: Boolean = false, selectedIds: Set<MediaId> = emptySet()) {
    var active by mutableStateOf(active)
        private set
    var selectedIds by mutableStateOf(selectedIds)
        private set

    fun isSelected(id: MediaId): Boolean = id in selectedIds

    /** Long press: enters the mode and selects [id] without toggling it off. */
    fun select(id: MediaId) {
        active = true
        selectedIds += id
    }

    fun toggle(id: MediaId) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun remove(ids: Set<MediaId>) {
        selectedIds -= ids
    }

    fun clear() {
        active = false
        selectedIds = emptySet()
    }

    companion object {
        val Saver = listSaver<MediaSelection, Any>(
            save = { listOf(it.active, ArrayList(it.selectedIds.map(MediaId::value))) },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                MediaSelection(
                    active = saved[0] as Boolean,
                    selectedIds = (saved[1] as List<String>).mapTo(mutableSetOf(), ::MediaId),
                )
            },
        )
    }
}

/** Survives activity recreation, e.g. while a system dialog started from the selection is open. */
@Composable
internal fun rememberMediaSelection(): MediaSelection =
    rememberSaveable(saver = MediaSelection.Saver) { MediaSelection() }

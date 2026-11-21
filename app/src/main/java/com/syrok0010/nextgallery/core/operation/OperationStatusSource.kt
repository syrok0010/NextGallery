package com.syrok0010.nextgallery.core.operation

import com.syrok0010.nextgallery.core.ui.UiText
import kotlinx.coroutines.flow.StateFlow

/** The operation kinds that can be shown in the library status surface. */
internal enum class OperationKind {
    Upload,
    AddToAlbum,
    RemoveFromAlbum,
    Delete,
}

/** A media reference used by an operation to mark a tile while it is active. */
internal sealed interface OperationMediaReference {
    data class LocalUri(val value: String) : OperationMediaReference

    data class RemoteFileId(val value: Long) : OperationMediaReference
}

internal data class OperationMediaActivity(
    val reference: OperationMediaReference,
    val progress: Float? = null,
)

/** Presentation-neutral data published by one long-lived operation. */
internal data class OperationStatusSnapshot(
    val kind: OperationKind,
    val title: UiText,
    val details: List<UiText> = emptyList(),
    val running: Boolean = false,
    val progress: Float? = null,
    val failed: Boolean = false,
    val media: List<OperationMediaActivity> = emptyList(),
)

/** A source owns its state machine; the app status surface only aggregates its snapshots. */
internal interface OperationStatusSource {
    val operations: StateFlow<List<OperationStatusSnapshot>>

    fun consumeSucceeded()

    fun consumeAll()
}

package com.syrok0010.nextgallery.data.local

import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaIdentityRegistry
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.mediaIdentityCandidate
import com.syrok0010.nextgallery.domain.media.MediaSourceIdentity
import com.syrok0010.nextgallery.domain.media.MediaSourceKind
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

data class LocalMediaMetadata(
    val contentUri: String,
    val displayName: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long?,
    val dateTakenMillis: Long?,
    val memoriesTimelineEpochSeconds: Long? = null,
    val imageUniqueId: String? = null,
    val dateModifiedSeconds: Long?,
    val dateAddedSeconds: Long?,
    val durationMillis: Long?,
    val isVideo: Boolean,
)

data class LocalMediaBatch(
    val metadata: List<LocalMediaMetadata>,
    val progress: LocalMediaIndexProgress,
)

data class LocalMediaIndexProgress(
    val indexedCount: Int,
    val totalCount: Int,
)

data class LocalMediaIndexState(
    val items: List<MediaItem>,
    val progress: LocalMediaIndexProgress?,
)

fun interface LocalMediaReader {
    fun readBatches(batchSize: Int): Flow<LocalMediaBatch>
}

fun interface LocalMediaChangeObserver {
    fun changes(): Flow<Unit>
}

interface LocalMediaProjectionStore {
    suspend fun loadLocalMediaProjection(): List<MediaItem>
    suspend fun saveLocalMediaBatch(items: List<MediaItem>)
    suspend fun finishLocalMediaReconciliation(contentUris: Set<String>)
}

@OptIn(FlowPreview::class)
class LocalMediaSource(
    private val reader: LocalMediaReader,
    private val projectionStore: LocalMediaProjectionStore,
    private val identityRegistry: MediaIdentityRegistry,
    private val changeObserver: LocalMediaChangeObserver,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val changeDebounce: Duration = DEFAULT_CHANGE_DEBOUNCE,
) {
    fun updates(reconcileRequests: Flow<Unit>): Flow<LocalMediaIndexState> = channelFlow {
        var publishedItems = projectionStore.loadLocalMediaProjection()
        send(LocalMediaIndexState(items = publishedItems, progress = null))

        suspend fun reconcile() {
            val freshItems = linkedMapOf<String, MediaItem>()
            var completed = false

            reader.readBatches(batchSize).collect { batch ->
                val mappedItems = mapMetadata(batch.metadata)
                projectionStore.saveLocalMediaBatch(mappedItems)
                mappedItems.forEach { item -> freshItems[item.localContentUri()] = item }

                if (batch.progress.indexedCount >= batch.progress.totalCount) {
                    projectionStore.finishLocalMediaReconciliation(freshItems.keys)
                    publishedItems = freshItems.values.toList().sortedForTimeline()
                    send(LocalMediaIndexState(items = publishedItems, progress = null))
                    completed = true
                } else {
                    val freshUris = freshItems.keys
                    publishedItems = (freshItems.values + publishedItems.filterNot { it.localContentUri() in freshUris })
                        .sortedForTimeline()
                    send(
                        LocalMediaIndexState(
                            items = publishedItems,
                            progress = batch.progress,
                        ),
                    )
                }
            }

            if (!completed) {
                projectionStore.finishLocalMediaReconciliation(emptySet())
                publishedItems = emptyList()
                send(LocalMediaIndexState(items = emptyList(), progress = null))
            }
        }

        val reconcileTriggers = Channel<Unit>(Channel.CONFLATED)
        launch(start = CoroutineStart.UNDISPATCHED) {
            changeObserver.changes().debounce(changeDebounce).collect {
                reconcileTriggers.trySend(Unit)
            }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            reconcileRequests.collect {
                reconcileTriggers.trySend(Unit)
            }
        }
        reconcileTriggers.trySend(Unit)
        for (ignored in reconcileTriggers) {
            reconcile()
        }
    }

    private suspend fun mapMetadata(metadata: List<LocalMediaMetadata>): List<MediaItem> {
        val drafts = metadata.mapNotNull { item ->
            val timestamp = item.timelineEpochSeconds() ?: return@mapNotNull null
            val aliases = MemoriesMediaIdentity.calculate(
                baseName = item.displayName,
                sizeBytes = item.sizeBytes ?: 0,
                dateTakenMillis = item.dateTakenMillis ?: 0,
                imageUniqueId = item.imageUniqueId,
            )
            LocalMediaDraft(item, timestamp, aliases)
        }
        val candidates = drafts.map { draft ->
            mediaIdentityCandidate(
                source = draft.metadata.sourceIdentity(),
                auid = draft.aliases.auid,
                buid = draft.aliases.buid,
            )
        }
        val resolution = identityRegistry.resolve(candidates)
        return drafts.map { draft ->
            val item = draft.metadata
            LocalMediaProjectionItem(
                mediaId = resolution.mediaIds.getValue(item.sourceIdentity()),
                contentUri = item.contentUri,
                displayName = item.displayName,
                mimeType = item.mimeType,
                width = item.width,
                height = item.height,
                takenAtEpochSeconds = draft.timelineEpochSeconds,
                modifiedAtEpochSeconds = item.dateModifiedSeconds,
                isVideo = item.isVideo,
                videoDurationSeconds = item.durationMillis?.takeIf { it > 0 }?.div(1_000),
                auid = draft.aliases.auid,
                buid = draft.aliases.buid,
            ).toMediaItem()
        }.sortedForTimeline()
    }

    private fun LocalMediaMetadata.timelineEpochSeconds(): Long? =
        memoriesTimelineEpochSeconds
            ?: dateTakenMillis?.takeIf { it > 0 }?.div(1_000)
            ?: dateModifiedSeconds?.takeIf { it > 0 }
            ?: dateAddedSeconds?.takeIf { it > 0 }

    private fun List<MediaItem>.sortedForTimeline(): List<MediaItem> =
        sortedWith(compareByDescending<MediaItem> { it.takenAtEpochSeconds }.thenByDescending { it.mediaId.value })

    private fun MediaItem.localContentUri(): String =
        (assetRef as MediaAssetRef.LocalContent).contentUri

    private fun LocalMediaMetadata.sourceIdentity() = MediaSourceIdentity(
        source = MediaSourceKind.Local,
        sourceKey = contentUri,
    )

    private companion object {
        const val DEFAULT_BATCH_SIZE = 200
        val DEFAULT_CHANGE_DEBOUNCE = 500.milliseconds
    }
}

private data class LocalMediaDraft(
    val metadata: LocalMediaMetadata,
    val timelineEpochSeconds: Long,
    val aliases: MediaAliases,
)

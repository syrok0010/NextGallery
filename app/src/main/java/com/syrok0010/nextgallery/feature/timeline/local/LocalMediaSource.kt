package com.syrok0010.nextgallery.feature.timeline.local

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.mediaIdentityCandidate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
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
    val generationModified: Long? = null,
    val volumeName: String? = null,
)

data class LocalMediaBatch(
    val metadata: List<LocalMediaMetadata>,
    val progress: LocalMediaIndexProgress,
    val unavailableContentUris: Set<String> = emptySet(),
)

data class LocalMediaIndexProgress(
    val indexedCount: Int,
    val totalCount: Int,
)

/** Progress-only updates retain the same immutable items instance. */
data class LocalMediaIndexState(
    val items: List<MediaItem>,
    val progress: LocalMediaIndexProgress?,
    val failure: Boolean = false,
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
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val publicationInterval: Duration = 250.milliseconds,
) {
    fun updates(reconcileRequests: Flow<Unit>): Flow<LocalMediaIndexState> = channelFlow {
        var publishedItems = projectionStore.loadLocalMediaProjection()
        send(LocalMediaIndexState(items = publishedItems, progress = null))

        suspend fun reconcile() {
            val itemsByUri = publishedItems.associateByTo(linkedMapOf()) { it.localContentUri() }
            val seenUris = mutableSetOf<String>()
            var dirty = false
            var lastPublication = TimeSource.Monotonic.markNow()
            var completed = false

            reader.readBatches(batchSize).collect { batch ->
                check(!completed) { "MediaStore emitted data after completion" }
                val mappedItems = mapMetadata(batch.metadata, itemsByUri)
                val changedItems = mappedItems.filter { itemsByUri[it.localContentUri()] != it }
                projectionStore.saveLocalMediaBatch(changedItems)
                mappedItems.forEach { item ->
                    val uri = item.localContentUri()
                    seenUris += uri
                    itemsByUri[uri] = item
                }
                dirty = dirty || changedItems.isNotEmpty()
                completed = batch.progress.indexedCount >= batch.progress.totalCount
                if (completed) {
                    seenUris.addAll(batch.unavailableContentUris)
                    projectionStore.finishLocalMediaReconciliation(seenUris)
                    dirty = itemsByUri.keys.retainAll(seenUris) || dirty
                }
                if (dirty && (completed || lastPublication.elapsedNow() >= publicationInterval)) {
                    publishedItems = itemsByUri.values.toList().sortedForTimeline()
                    dirty = false
                    lastPublication = TimeSource.Monotonic.markNow()
                }
                send(LocalMediaIndexState(publishedItems, if (completed) null else batch.progress))
            }
            check(completed) { "MediaStore scan ended without a complete result" }
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
            try {
                reconcile()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the observer alive; another explicit or MediaStore trigger can recover.
                send(LocalMediaIndexState(publishedItems, progress = null, failure = true))
            }
        }
    }.flowOn(computationDispatcher)

    private suspend fun mapMetadata(
        metadata: List<LocalMediaMetadata>,
        existingItems: Map<String, MediaItem>,
    ): List<MediaItem> {
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
        val unchangedItems = drafts.mapNotNull { draft ->
            existingItems[draft.metadata.contentUri]?.takeIf { existing ->
                draft.toMediaItem(existing.mediaId) == existing
            }
        }.associateBy { it.localContentUri() }
        val candidates = drafts.filterNot { it.metadata.contentUri in unchangedItems }.map { draft ->
            mediaIdentityCandidate(
                source = draft.metadata.sourceIdentity(),
                auid = draft.aliases.auid,
                buid = draft.aliases.buid,
            )
        }
        val resolution = identityRegistry.resolve(candidates)
        return drafts.map { draft ->
            unchangedItems[draft.metadata.contentUri]
                ?: draft.toMediaItem(resolution.mediaIds.getValue(draft.metadata.sourceIdentity()))
        }
    }

    private fun LocalMediaDraft.toMediaItem(mediaId: MediaId): MediaItem = LocalMediaProjectionItem(
        mediaId = mediaId,
        contentUri = metadata.contentUri,
        displayName = metadata.displayName,
        mimeType = metadata.mimeType,
        width = metadata.width,
        height = metadata.height,
        takenAtEpochSeconds = timelineEpochSeconds,
        modifiedAtEpochSeconds = metadata.dateModifiedSeconds,
        isVideo = metadata.isVideo,
        videoDurationSeconds = metadata.durationMillis?.takeIf { it > 0 }?.div(1_000),
        auid = aliases.auid,
        buid = aliases.buid,
    ).toMediaItem()

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

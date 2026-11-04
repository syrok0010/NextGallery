package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.operation.OperationKind
import com.syrok0010.nextgallery.core.operation.OperationMediaActivity
import com.syrok0010.nextgallery.core.operation.OperationMediaReference
import com.syrok0010.nextgallery.core.operation.OperationStatusSnapshot
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/** Which copies of objects that exist both on the phone and in the cloud go to the trash. */
internal enum class DeletionScope { Everywhere, PhoneOnly, CloudOnly }

/** Copies to delete: local content URIs for the system trash, cloud file ids for Nextcloud. */
@Serializable
internal data class DeletionTargets(val contentUris: List<String>, val fileIds: List<Long>)

/** How many selected objects have only a phone copy, only a cloud copy, or both. */
internal data class DeletionPlan(val phoneOnly: Int, val cloudOnly: Int, val merged: Int) {
    val total: Int get() = phoneOnly + cloudOnly + merged
}

/**
 * Copies of the selection, fixed before the system confirmation dialog. The physical references
 * stay inside the deletion module: the UI only shows [plan] and returns the user's scope.
 */
internal class DeletionPreparation private constructor(
    private val phoneOnly: List<String>,
    private val cloudOnly: List<Long>,
    private val merged: List<MergedCopies>,
) {
    val plan = DeletionPlan(phoneOnly.size, cloudOnly.size, merged.size)

    fun confirm(scope: DeletionScope): DeletionTargets =
        DeletionTargets(
            contentUris = phoneOnly +
                if (scope == DeletionScope.CloudOnly) emptyList() else merged.map { it.contentUri },
            fileIds = (
                cloudOnly +
                    if (scope == DeletionScope.PhoneOnly) emptyList() else merged.map { it.fileId }
                ).distinct(),
        )

    private class MergedCopies(val contentUri: String, val fileId: Long)

    companion object {
        /** Objects without any copy left are skipped; null when nothing remains to delete. */
        internal fun of(items: List<MediaItem>): DeletionPreparation? {
            val phoneOnly = mutableListOf<String>()
            val cloudOnly = mutableListOf<Long>()
            val merged = mutableListOf<MergedCopies>()
            items.forEach { item ->
                val local = item.localCopy?.contentUri?.let { mediaStoreItemUri(it, item.isVideo) }
                val remote = item.remoteFileId
                when {
                    local != null && remote != null -> merged += MergedCopies(local, remote)
                    local != null -> phoneOnly += local
                    remote != null -> cloudOnly += remote
                }
            }
            return DeletionPreparation(phoneOnly, cloudOnly, merged).takeIf { it.plan.total > 0 }
        }
    }
}

/**
 * The local index reads `MediaStore.Files`, but trash requests only accept image, video and audio
 * item URIs, so the adapter identifier is rewritten inside this module.
 */
internal fun mediaStoreItemUri(contentUri: String, isVideo: Boolean): String {
    val prefix = "content://media/"
    val parts = contentUri.removePrefix(prefix).split('/')
    if (!contentUri.startsWith(prefix) || parts.size != 3 || parts[1] != "file") return contentUri
    val collection = if (isVideo) "video" else "images"
    return "$prefix${parts[0]}/$collection/media/${parts[2]}"
}

/**
 * Cloud copies queued or being deleted. [total] and [processed] count files of all deletions
 * started since the queue was last empty, so a queued deletion extends the same progress.
 */
internal data class DeletionProgress(val fileIds: Set<Long>, val total: Int, val processed: Int)

internal data class CloudDeleteResult(val deleted: Int, val failed: Int)

/** Results of deletions not shown yet; a later deletion adds to them. */
internal data class DeletionResult(
    val phoneTrashed: Int,
    val cloud: CloudDeleteResult,
    val phoneFailed: Int = 0,
) {
    val failed: Boolean get() = phoneFailed > 0 || cloud.failed > 0

    operator fun plus(other: DeletionResult) =
        DeletionResult(
            phoneTrashed = phoneTrashed + other.phoneTrashed,
            cloud = CloudDeleteResult(
                deleted = cloud.deleted + other.cloud.deleted,
                failed = cloud.failed + other.cloud.failed,
            ),
            phoneFailed = phoneFailed + other.phoneFailed,
        )
}

/** One atomic snapshot: a running deletion and an unshown result may coexist. */
internal data class DeletionState(
    val running: DeletionProgress? = null,
    val unseen: DeletionResult? = null,
)

/**
 * Counts the URIs that MediaStore really marks as trashed: the system dialog answers RESULT_OK
 * even when single items fail to move.
 */
internal fun interface PhoneTrashCheck {
    suspend fun trashed(contentUris: List<String>): Int
}

/**
 * Runs the cloud half of a deletion in a process-scoped coroutine: the phone half has already
 * been confirmed in the system trash dialog, so the result must survive the screen.
 */
internal class MediaDeletionRepository(
    private val sessions: SessionStore,
    private val library: MediaLibraryIndex,
    private val cloud: CloudTrash,
    private val phoneTrash: PhoneTrashCheck,
    private val scope: CoroutineScope,
    private val onDeleted: () -> Unit,
) : OperationStatusSource {
    private val mutableState = MutableStateFlow(DeletionState())
    val state = mutableState.asStateFlow()
    override val operations = state
        .map(::deletionOperationSnapshots)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val deletionMutex = Mutex()

    /** Parent of every queued deletion, so a session change cancels the whole queue. */
    private var operationParent = SupervisorJob(scope.coroutineContext[Job])

    init {
        scope.launch {
            var owner: AccountCredentials? = null
            sessions.session.collect { session ->
                val current = (session as? SessionUiState.SignedIn)?.credentials
                // The first emission only records the owner; results never cross accounts.
                if (current == null || (owner != null && current != owner)) {
                    operationParent.cancel()
                    operationParent = SupervisorJob(scope.coroutineContext[Job])
                    mutableState.value = DeletionState()
                }
                owner = current
            }
        }
    }

    /**
     * Resolves the selected objects before the UI asks the user for a scope. Objects that left the
     * library since they were selected are already gone and are skipped; null if none is left.
     */
    fun prepare(mediaIds: Collection<MediaId>): DeletionPreparation? {
        val library = library.state.value
        return DeletionPreparation.of(mediaIds.distinct().mapNotNull { library[it] })
    }

    /** Requests run one after another; a new one never cancels a deletion already started. */
    fun delete(targets: DeletionTargets) {
        val fileIds = targets.fileIds
        if (fileIds.isNotEmpty()) {
            mutableState.update { state ->
                DeletionProgress(
                    fileIds = state.running?.fileIds.orEmpty() + fileIds,
                    total = (state.running?.total ?: 0) + fileIds.size,
                    processed = state.running?.processed ?: 0,
                )
                    .let { running -> state.copy(running = running) }
            }
        }
        scope.launch(operationParent) {
            deletionMutex.withLock {
                val phoneTrashed = if (targets.contentUris.isEmpty()) {
                0
                } else {
                    try {
                        phoneTrash.trashed(targets.contentUris)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The dialog was confirmed; an unreadable result is not reported as failure.
                        targets.contentUris.size
                    }
                }
                val cloud = if (fileIds.isEmpty()) {
                    CloudDeleteResult(deleted = 0, failed = 0)
                } else {
                    val credentials = credentials()
                    credentials?.let { deleteCloud(it, fileIds) }
                        ?: CloudDeleteResult(deleted = 0, failed = fileIds.size)
                }
                val done = DeletionResult(
                    phoneTrashed = phoneTrashed,
                    cloud = cloud,
                    phoneFailed = targets.contentUris.size - phoneTrashed,
                )
                mutableState.update { state ->
                    // The ids leave only now: the tiles stay marked until the result is in.
                    state.copy(
                        running = state.running
                            ?.copy(fileIds = state.running.fileIds - fileIds.toSet())
                            ?.takeIf { it.fileIds.isNotEmpty() },
                        unseen = state.unseen?.plus(done) ?: done,
                    )
                }
                onDeleted()
            }
        }
    }

    /** Called once the UI has shown the result. */
    fun consume() {
        mutableState.update { it.copy(unseen = null) }
    }

    override fun consumeSucceeded() {
        mutableState.update { state ->
            if (state.unseen?.failed == false) state.copy(unseen = null) else state
        }
    }

    override fun consumeAll() {
        consume()
    }

    private fun credentials(): AccountCredentials? =
        (sessions.session.value as? SessionUiState.SignedIn)?.credentials

    private suspend fun deleteCloud(
        credentials: AccountCredentials,
        fileIds: List<Long>,
    ): CloudDeleteResult {
        val permits = Semaphore(PARALLEL_CLOUD_DELETES)
        val outcomes = coroutineScope {
            fileIds
                .distinct()
                .map { fileId ->
                    async {
                        permits.withPermit {
                            val deleted = try {
                                cloud.trash(credentials, fileId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            mutableState.update { state ->
                                state.copy(
                                    running = state.running?.copy(
                                        processed = state.running.processed + 1,
                                    ),
                                )
                            }
                            deleted
                        }
                    }
                }.awaitAll()
        }
        return CloudDeleteResult(
            deleted = outcomes.count { it },
            failed = outcomes.count { !it },
        )
    }

    private companion object {
        const val PARALLEL_CLOUD_DELETES = 4
    }
}

private fun deletionOperationSnapshots(
    state: DeletionState,
): List<OperationStatusSnapshot> = buildList {
    state.running?.let {
        add(
            OperationStatusSnapshot(
                kind = OperationKind.Delete,
                title = com.syrok0010.nextgallery.core.ui.uiText(
                    R.string.delete_progress,
                    it.processed,
                    it.total,
                ),
                running = true,
                progress = if (it.total == 0) null else it.processed.toFloat() / it.total,
                media = it.fileIds.map {
                    OperationMediaActivity(OperationMediaReference.RemoteFileId(it))
                },
            ),
        )
    }
    state.unseen?.let {
        val lines = mediaDeletionMessage(it)
        if (lines.isNotEmpty()) {
            add(
                OperationStatusSnapshot(
                    kind = OperationKind.Delete,
                    title = com.syrok0010.nextgallery.core.ui.uiText(
                        if (it.failed) R.string.delete_done_with_errors else R.string.delete_done,
                    ),
                    details = lines,
                    failed = it.failed,
                ),
            )
        }
    }
}

/** Lines of a result; zero counters stay silent. */
internal fun mediaDeletionMessage(result: DeletionResult): List<UiText> =
    buildList {
        if (result.phoneTrashed > 0) add(uiText(R.string.delete_done_phone, result.phoneTrashed))
        if (result.phoneFailed > 0) {
            add(uiText(R.string.delete_done_phone_failed, result.phoneFailed))
        }
        if (result.cloud.deleted > 0) add(uiText(R.string.delete_done_cloud, result.cloud.deleted))
        if (result.cloud.failed > 0) add(uiText(R.string.delete_done_failed, result.cloud.failed))
    }

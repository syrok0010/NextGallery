package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.R
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

internal sealed interface MediaDeletionState {
    data object Idle : MediaDeletionState

    data class Running(val count: Int) : MediaDeletionState

    data class Done(
        val phoneTrashed: Int,
        val cloud: CloudDeleteResult,
        val phoneFailed: Int = 0,
    ) : MediaDeletionState {
        operator fun plus(other: Done) =
            Done(
                phoneTrashed = phoneTrashed + other.phoneTrashed,
                cloud = CloudDeleteResult(
                    deleted = cloud.deleted + other.cloud.deleted,
                    failed = cloud.failed + other.cloud.failed,
                ),
                phoneFailed = phoneFailed + other.phoneFailed,
            )
    }
}

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
    private val deleter: MemoriesFileDeleter,
    private val phoneTrash: PhoneTrashCheck,
    private val scope: CoroutineScope,
    private val onDeleted: () -> Unit,
) {
    private val mutableState = MutableStateFlow<MediaDeletionState>(MediaDeletionState.Idle)
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    /** Results not yet shown; a later operation adds to them instead of replacing them. */
    private var unshown: MediaDeletionState.Done? = null

    /** Parent of every queued deletion, so a session change cancels the whole queue. */
    private var operations = SupervisorJob(scope.coroutineContext[Job])

    init {
        scope.launch {
            var owner: AccountCredentials? = null
            sessions.session.collect { session ->
                val current = (session as? SessionUiState.SignedIn)?.credentials
                // The first emission only records the owner; results never cross accounts.
                if (current == null || (owner != null && current != owner)) {
                    operations.cancel()
                    operations = SupervisorJob(scope.coroutineContext[Job])
                    job = null
                    unshown = null
                    mutableState.value = MediaDeletionState.Idle
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
        val previous = job
        job = scope.launch(operations) {
            previous?.join()
            val fileIds = targets.fileIds
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
            if (fileIds.isNotEmpty()) mutableState.value = MediaDeletionState.Running(fileIds.size)
            val cloud = if (fileIds.isEmpty()) {
                CloudDeleteResult(deleted = 0, failed = 0)
            } else {
                val credentials = credentials()
                try {
                    credentials?.let { deleter.delete(it, fileIds) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: CloudDeleteResult(deleted = 0, failed = fileIds.size)
            }
            val done = MediaDeletionState.Done(
                phoneTrashed = phoneTrashed,
                cloud = cloud,
                phoneFailed = targets.contentUris.size - phoneTrashed,
            )
            val total = unshown?.plus(done) ?: done
            unshown = total
            mutableState.value = total
            onDeleted()
        }
    }

    /** Called once the UI has shown a terminal result. */
    fun consume() {
        if (mutableState.value is MediaDeletionState.Done) {
            unshown = null
            mutableState.value = MediaDeletionState.Idle
        }
    }

    private fun credentials(): AccountCredentials? =
        (sessions.session.value as? SessionUiState.SignedIn)?.credentials
}

/** Message parts joined by the snackbar; zero counters stay silent. */
internal fun mediaDeletionMessage(state: MediaDeletionState): List<UiText>? =
    when (state) {
        MediaDeletionState.Idle -> null

        is MediaDeletionState.Running -> listOf(uiText(R.string.delete_running, state.count))

        is MediaDeletionState.Done -> buildList {
            if (state.phoneTrashed > 0) add(uiText(R.string.delete_done_phone, state.phoneTrashed))
            if (state.phoneFailed > 0) {
                add(
                    uiText(R.string.delete_done_phone_failed, state.phoneFailed),
                )
            }
            if (state.cloud.deleted > 0) {
                add(
                    uiText(R.string.delete_done_cloud, state.cloud.deleted),
                )
            }
            if (state.cloud.failed > 0) add(uiText(R.string.delete_done_failed, state.cloud.failed))
        }.ifEmpty { null }
    }

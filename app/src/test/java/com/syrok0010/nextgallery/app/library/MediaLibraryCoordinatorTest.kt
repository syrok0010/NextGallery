package com.syrok0010.nextgallery.app.library

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaBatch
import com.syrok0010.nextgallery.feature.library.local.LocalMediaChangeObserver
import com.syrok0010.nextgallery.feature.library.local.LocalMediaIndexProgress
import com.syrok0010.nextgallery.feature.library.local.LocalMediaMetadata
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.library.local.LocalMediaProjectionStore
import com.syrok0010.nextgallery.feature.library.local.LocalMediaReader
import com.syrok0010.nextgallery.feature.library.local.LocalMediaSource
import com.syrok0010.nextgallery.feature.timeline.MemoriesConfig
import com.syrok0010.nextgallery.feature.timeline.RemoteTimelineSource
import com.syrok0010.nextgallery.feature.timeline.TimelineDay
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshot
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.feature.timeline.remote.InMemoryMediaIdentityRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryCoordinatorTest {
    private val sessions = SessionStore(NoStoredCredentials)
    private val remote = Remote()
    private val reads = AtomicInteger()
    private val permissions =
        MutableStateFlow<LocalMediaPermissionMode?>(LocalMediaPermissionMode.Full)
    private val index = MediaLibraryIndex()
    private val coordinator = MediaLibraryCoordinator(
        index = index,
        sessions = sessions,
        remoteSource = remote,
        localMedia = LocalMediaSource(
            reader = LocalMediaReader {
                reads.incrementAndGet()
                flowOf(LocalMediaBatch(listOf(phoneFile), LocalMediaIndexProgress(1, 1)))
            },
            projectionStore = InMemoryProjection(),
            identityRegistry = InMemoryMediaIdentityRegistry { MediaId(PHONE_FILE) },
            changeObserver = LocalMediaChangeObserver { emptyFlow() },
        ),
        permissions = permissions,
    )
    private val publications = CopyOnWriteArrayList<LibraryPublication>()

    @Test fun signingInJoinsPhoneFilesAndTheAccountsCloud() =
        running {
            sessions.signIn(first)
            waitFor { shown() == setOf(PHONE_FILE, cloudPhoto(first)) }
            assertEquals(shown(), index.state.value.keys.map(MediaId::value).toSet())
        }

    @Test fun signedOutLibraryHoldsNothingAndDoesNotScanThePhone() =
        running {
            waitFor { publications.isNotEmpty() }
            delay(SETTLE_MS)

            assertTrue(publications.all { it.library.items.isEmpty() })
            assertTrue(index.state.value.isEmpty())
            assertEquals(0, reads.get())
            assertEquals(0, remote.initialLoads.get())
        }

    @Test fun withoutPhoneAccessOnlyCloudPhotosAreShown() =
        running {
            permissions.value = LocalMediaPermissionMode.Denied
            sessions.signIn(first)
            waitFor { shown() == setOf(cloudPhoto(first)) }

            permissions.value = LocalMediaPermissionMode.Full
            waitFor { shown() == setOf(PHONE_FILE, cloudPhoto(first)) }
        }

    @Test fun refreshReloadsTheCloudAndRescansThePhone() =
        running {
            sessions.signIn(first)
            waitFor { shown() == setOf(PHONE_FILE, cloudPhoto(first)) && reads.get() == 1 }
            assertEquals(1, remote.initialLoads.get())

            coordinator.refresh()

            waitFor { remote.initialLoads.get() == 2 && reads.get() == 2 }
        }

    private fun running(block: suspend CoroutineScope.() -> Unit) =
        runBlocking {
            val job: Job = launch { coordinator.run { publications += it } }
            try {
                block()
            } finally {
                job.cancelAndJoin()
            }
        }

    private fun shown(): Set<String> = publications.lastOrNull()?.ids().orEmpty()

    private fun LibraryPublication.ids(): Set<String> =
        library.items.mapTo(mutableSetOf()) {
            it.mediaId.value
        }

    private suspend fun waitFor(condition: () -> Boolean) =
        withTimeout(5_000) { while (!condition()) delay(10) }

    private inner class Remote : RemoteTimelineSource {
        val initialLoads = AtomicInteger()

        override suspend fun loadCachedTimeline(
            credentials: AccountCredentials,
        ): TimelineSnapshot? = null

        override suspend fun loadInitialTimeline(
            credentials: AccountCredentials,
        ): TimelineSnapshot {
            initialLoads.incrementAndGet()
            val photo = cloudItem(credentials)
            return TimelineSnapshotAssembler.assemble(
                config = config,
                days = listOf(TimelineDay(photo.dayId, 1)),
                mediaItems = listOf(photo),
                loadedDayIds = setOf(photo.dayId),
            )
        }

        override suspend fun loadTimelineDays(
            credentials: AccountCredentials,
            dayIds: List<Int>,
        ): List<MediaItem> = emptyList()
    }

    private class InMemoryProjection : LocalMediaProjectionStore {
        @Volatile private var items = emptyList<MediaItem>()

        override suspend fun loadLocalMediaProjection(): List<MediaItem> = items

        override suspend fun saveLocalMediaBatch(items: List<MediaItem>) {
            val uris = items.mapTo(mutableSetOf()) { it.contentUri() }
            this.items = this.items.filterNot { it.contentUri() in uris } + items
        }

        override suspend fun finishLocalMediaReconciliation(contentUris: Set<String>) {
            items = items.filter { it.contentUri() in contentUris }
        }

        private fun MediaItem.contentUri() = (assetRef as MediaAssetRef.LocalContent).contentUri
    }

    private object NoStoredCredentials : CredentialsStore {
        override fun load(): AccountCredentials? = null
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    }

    private companion object {
        const val PHONE_FILE = "content://media/external/images/media/1"
        const val SETTLE_MS = 300L

        val first = AccountCredentials("https://cloud.example.test", "first", "password", "first")

        /** The only phone file; the registry publishes it as [PHONE_FILE]. */
        val phoneFile = LocalMediaMetadata(
            contentUri = PHONE_FILE,
            displayName = "phone.jpg",
            mimeType = "image/jpeg",
            width = 100,
            height = 100,
            sizeBytes = 1,
            dateTakenMillis = 1_700_000_000_000,
            memoriesTimelineEpochSeconds = null,
            dateModifiedSeconds = null,
            dateAddedSeconds = null,
            durationMillis = null,
            isVideo = false,
        )

        val config = MemoriesConfig("10.1.0", "/Photos", false, false, false, false, false, false)

        fun cloudPhoto(account: AccountCredentials) = "cloud:${account.userId}"

        fun cloudItem(account: AccountCredentials) =
            MediaItem(
                mediaId = MediaId(cloudPhoto(account)),
                dayId = 19_700,
                displayName = "${account.userId}.jpg",
                mimeType = "image/jpeg",
                width = 100,
                height = 100,
                etag = "etag",
                livePhotoId = null,
                auid = "auid-${account.userId}",
                buid = "buid-${account.userId}",
                sharedBy = null,
                takenAtEpochSeconds = 19_700 * 86_400L,
                isVideo = false,
                videoDurationSeconds = null,
                isFavorite = false,
                isHidden = false,
                assetRef = MediaAssetRef.MemoriesFile(account.userId.length.toLong()),
            )
    }
}

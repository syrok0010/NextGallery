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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaLibraryCoordinatorTest {
    private val sessions = SessionStore(NoStoredCredentials)
    private val remote = Remote()
    private val reads = AtomicInteger()

    @Volatile private var phoneFiles = listOf(phoneFile(PHONE_FILE))
    private val phoneIds = ArrayDeque(listOf(PHONE_FILE, LATER_PHONE_FILE))
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
                val files = phoneFiles
                flowOf(LocalMediaBatch(files, LocalMediaIndexProgress(files.size, files.size)))
            },
            projectionStore = InMemoryProjection(),
            identityRegistry = InMemoryMediaIdentityRegistry { MediaId(phoneIds.removeFirst()) },
            changeObserver = LocalMediaChangeObserver { emptyFlow() },
        ),
        permissions = permissions,
    )
    private val publications = CopyOnWriteArrayList<LibraryPublication>()

    @Test fun signingInJoinsPhoneFilesAndTheAccountsCloud() =
        running {
            sessions.signIn(account)
            waitFor { shown() == setOf(PHONE_FILE, CLOUD_PHOTO) }
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
            sessions.signIn(account)
            waitFor { shown() == setOf(CLOUD_PHOTO) }

            permissions.value = LocalMediaPermissionMode.Full
            waitFor { shown() == setOf(PHONE_FILE, CLOUD_PHOTO) }
        }

    @Test fun refreshReloadsTheCloudAndRescansThePhone() =
        running {
            sessions.signIn(account)
            waitFor { shown() == setOf(PHONE_FILE, CLOUD_PHOTO) }

            phoneFiles = listOf(phoneFile(PHONE_FILE), phoneFile(LATER_PHONE_FILE))
            remote.photo = cloudItem(LATER_CLOUD_PHOTO)
            coordinator.refresh()

            waitFor { shown() == setOf(PHONE_FILE, LATER_PHONE_FILE, LATER_CLOUD_PHOTO) }
            assertEquals(shown(), index.state.value.keys.map(MediaId::value).toSet())
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

    private suspend fun waitFor(condition: () -> Boolean) {
        val met = withTimeoutOrNull(5_000) { while (!condition()) delay(10) }
        if (met == null) fail("Library shows ${shown()}, index holds ${index.state.value.keys}")
    }

    private inner class Remote : RemoteTimelineSource {
        val initialLoads = AtomicInteger()

        @Volatile var photo = cloudItem(CLOUD_PHOTO)

        override suspend fun loadCachedTimeline(
            credentials: AccountCredentials,
        ): TimelineSnapshot? = null

        override suspend fun loadInitialTimeline(
            credentials: AccountCredentials,
        ): TimelineSnapshot {
            initialLoads.incrementAndGet()
            val photo = this.photo
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
    }

    private companion object {
        const val PHONE_FILE = "content://media/external/images/media/1"
        const val LATER_PHONE_FILE = "content://media/external/images/media/2"
        const val CLOUD_PHOTO = "cloud:1"
        const val LATER_CLOUD_PHOTO = "cloud:2"
        const val SETTLE_MS = 300L

        val account = AccountCredentials("https://cloud.example.test", "user", "password", "user")

        /** Phone files differ in time so the registry never takes them for one photo (AUID is time + size). */
        fun phoneFile(contentUri: String) =
            LocalMediaMetadata(
                contentUri = contentUri,
                displayName = "${contentUri.substringAfterLast('/')}.jpg",
                mimeType = "image/jpeg",
                width = 100,
                height = 100,
                sizeBytes = 1,
                dateTakenMillis = 1_700_000_000_000 +
                    contentUri
                        .substringAfterLast(
                            '/',
                        ).toLong() *
                    1_000,
                memoriesTimelineEpochSeconds = null,
                dateModifiedSeconds = null,
                dateAddedSeconds = null,
                durationMillis = null,
                isVideo = false,
            )

        val config = MemoriesConfig("10.1.0", "/Photos", false, false, false, false, false, false)

        fun cloudItem(id: String) =
            MediaItem(
                mediaId = MediaId(id),
                dayId = 19_700,
                displayName = "$id.jpg",
                mimeType = "image/jpeg",
                width = 100,
                height = 100,
                etag = "etag",
                livePhotoId = null,
                auid = "auid-$id",
                buid = "buid-$id",
                sharedBy = null,
                takenAtEpochSeconds = 19_700 * 86_400L,
                isVideo = false,
                videoDurationSeconds = null,
                isFavorite = false,
                isHidden = false,
                assetRef = MediaAssetRef.MemoriesFile(id.substringAfter(':').toLong()),
            )
    }
}

package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.mediaIdentityCandidate
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.library.projectMediaLibrary
import com.syrok0010.nextgallery.feature.timeline.remote.InMemoryMediaIdentityRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The follow-up only turns a finished upload into a link request; the linking rules themselves
 * (no replacing, no taking a copy from another phone file) are tested on Room in
 * RoomMediaIdentityRegistryTest.
 */
class UploadedCopiesFollowUpTest {
    private val registry = InMemoryMediaIdentityRegistry()
    private val sessions = SessionStore(SignedInStore)
    private val followUp = UploadedCopiesFollowUp(registry)

    @Test
    fun `uploaded copy joins the indexed phone file as one object`() =
        runBlocking {
            val localId = resolveLocal()

            // The upload keeps the URI the picker gave; the index keys the file by its external volume.
            assertTrue(followUp.link(PICKED_CONTENT_URI, FILE_ID))
            val remoteId = resolveRemote()

            assertEquals(localId, remoteId)
            val library = projectMediaLibrary(
                localItems = listOf(localItem(localId)),
                remoteItems = listOf(remoteItem(remoteId)),
            )
            assertTrue(library.items.single().assetRef is MediaAssetRef.LocalFirst)
        }

    private suspend fun resolveLocal(): MediaId {
        val source = MediaSourceIdentity(MediaSourceKind.Local, CONTENT_URI)
        val candidate = mediaIdentityCandidate(source, auid = LOCAL_AUID, buid = LOCAL_BUID)
        return registry.resolve(listOf(candidate)).mediaIds.getValue(source)
    }

    /** Memories 10.1 returns neither AUID nor BUID. */
    private suspend fun resolveRemote(): MediaId {
        val source = MediaSourceIdentity(MediaSourceKind.Memories, FILE_ID.toString())
        val candidate = mediaIdentityCandidate(source, auid = null, buid = null)
        return registry.resolve(listOf(candidate)).mediaIds.getValue(source)
    }

    private fun localItem(mediaId: MediaId) =
        item(mediaId, LOCAL_AUID, LOCAL_BUID, MediaAssetRef.LocalContent(CONTENT_URI, TAKEN_AT, 1))

    private fun remoteItem(mediaId: MediaId) =
        item(mediaId, auid = null, buid = null, MediaAssetRef.MemoriesFile(FILE_ID))

    private fun item(mediaId: MediaId, auid: String?, buid: String?, assetRef: MediaAssetRef) =
        MediaItem(
            mediaId = mediaId,
            dayId = 19_869,
            displayName = "NG-upload.jpg",
            mimeType = "image/jpeg",
            width = 1_080,
            height = 1_920,
            etag = null,
            livePhotoId = null,
            auid = auid,
            buid = buid,
            sharedBy = null,
            takenAtEpochSeconds = TAKEN_AT,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = assetRef,
        )

    private companion object {
        const val CONTENT_URI = "content://media/external/images/media/42"
        const val PICKED_CONTENT_URI = "content://media/external_primary/images/media/42"
        const val FILE_ID = 183L
        const val TAKEN_AT = 1_716_900_000L
        const val LOCAL_AUID = "local-auid"
        const val LOCAL_BUID = "local-buid"
    }
}

private object SignedInStore : CredentialsStore {
    override fun load(): AccountCredentials = CREDENTIALS

    override fun save(credentials: AccountCredentials) = Unit
}

private val CREDENTIALS = AccountCredentials("http://test.example", "test", "app-password", "test")

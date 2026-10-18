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
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.feature.library.projectMediaLibrary
import com.syrok0010.nextgallery.feature.timeline.remote.InMemoryMediaIdentityRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadedCopiesTest {
    private val registry = InMemoryMediaIdentityRegistry()
    private var refreshes = 0
    private val sessions = SessionStore(SignedInStore)
    private val copies = UploadedCopies(registry, sessions) { refreshes++ }

    @Test
    fun `file id is read from the OC-FileId header`() {
        assertEquals(183L, uploadedFileId("00000183ocmsigiy9k16"))
        assertEquals(123_456_789L, uploadedFileId("123456789ocmsigiy9k16"))
        assertNull(uploadedFileId(null))
        assertNull(uploadedFileId(""))
        assertNull(uploadedFileId("ocmsigiy9k16"))
        assertNull(uploadedFileId("00000000ocmsigiy9k16"))
    }

    @Test
    fun `linked server copy joins the phone file as one object`() =
        runBlocking {
            val localId = resolveLocal()

            assertTrue(copies.link(CREDENTIALS, CONTENT_URI, FILE_ID))
            val remoteId = resolveRemote()

            assertEquals(localId, remoteId)
            val library = projectMediaLibrary(
                localItems = listOf(localItem(localId)),
                remoteItems = listOf(remoteItem(remoteId)),
            )
            val item = library.items.single()
            assertTrue(item.assetRef is MediaAssetRef.LocalFirst)
        }

    @Test
    fun `without the link copies with different aliases stay separate`() =
        runBlocking {
            val localId = resolveLocal()
            val remoteId = resolveRemote()

            assertNotEquals(localId, remoteId)
            val library = projectMediaLibrary(
                localItems = listOf(localItem(localId)),
                remoteItems = listOf(remoteItem(remoteId)),
            )
            assertEquals(2, library.items.size)
        }

    @Test
    fun `link replaces a media id the server file already received`() =
        runBlocking {
            val localId = resolveLocal()
            val earlyRemoteId = resolveRemote()

            assertTrue(copies.link(CREDENTIALS, CONTENT_URI, FILE_ID))

            assertNotEquals(localId, earlyRemoteId)
            assertEquals(localId, resolveRemote())
        }

    @Test
    fun `exact link outranks the aliases of an early server object`() =
        runBlocking {
            val localId = resolveLocal()
            val earlyRemoteId = resolveRemote(auid = "server-auid", buid = "server-buid")

            assertTrue(copies.link(CREDENTIALS, CONTENT_URI, FILE_ID))

            assertNotEquals(localId, earlyRemoteId)
            assertEquals(localId, resolveRemote(auid = "server-auid", buid = "server-buid"))
        }

    @Test
    fun `a new upload of the same phone file keeps one server copy per object`() =
        runBlocking {
            val localId = resolveLocal()
            assertTrue(copies.link(CREDENTIALS, CONTENT_URI, FILE_ID))

            assertTrue(copies.link(CREDENTIALS, CONTENT_URI, SECOND_FILE_ID))

            // Both server files have the phone file's AUID.
            val secondId = resolveRemote(SECOND_FILE_ID, auid = LOCAL_AUID)
            val firstId = resolveRemote(auid = LOCAL_AUID)
            assertEquals(localId, secondId)
            assertNotEquals(localId, firstId)
            val library = projectMediaLibrary(
                localItems = listOf(localItem(localId)),
                remoteItems = listOf(
                    remoteItem(firstId, auid = LOCAL_AUID),
                    remoteItem(secondId, SECOND_FILE_ID, LOCAL_AUID),
                ),
            )
            assertEquals(2, library.items.size)
            assertEquals(2, library.items.map { it.mediaId }.toSet().size)
            assertEquals(1, library.items.count { it.assetRef is MediaAssetRef.LocalFirst })
        }

    @Test
    fun `server duplicates with one AUID stay separate tiles next to the phone file`() =
        runBlocking {
            val firstId = resolveRemote(auid = LOCAL_AUID)
            val secondId = resolveRemote(SECOND_FILE_ID, auid = LOCAL_AUID)
            val localId = resolveLocal()

            val library = projectMediaLibrary(
                localItems = listOf(localItem(localId)),
                remoteItems = listOf(
                    remoteItem(firstId, auid = LOCAL_AUID),
                    remoteItem(secondId, SECOND_FILE_ID, LOCAL_AUID),
                ),
            )
            assertEquals(2, library.items.size)
            assertEquals(2, library.items.map { it.mediaId }.toSet().size)
            assertEquals(1, library.items.count { it.assetRef is MediaAssetRef.LocalFirst })
        }

    @Test
    fun `upload finishing after logout or account switch is not linked`() =
        runBlocking {
            val localId = resolveLocal()

            sessions.signOut()
            assertFalse(copies.link(CREDENTIALS, CONTENT_URI, FILE_ID))
            sessions.signIn(CREDENTIALS.copy(serverUrl = "https://other.example"))
            assertFalse(copies.link(CREDENTIALS, CONTENT_URI, FILE_ID))

            assertNotEquals(localId, resolveRemote())
        }

    @Test
    fun `unknown phone file is not linked`() =
        runBlocking {
            assertFalse(copies.link(CREDENTIALS, CONTENT_URI, FILE_ID))
            assertEquals(0, refreshes)
        }

    @Test
    fun `follow-up links a created file and refreshes the library after the batch`() =
        runBlocking {
            val localId = resolveLocal()
            val followUp = copies.followUp()

            followUp.uploaded(CREDENTIALS, uploadedFile(fileId = FILE_ID))
            assertEquals(0, refreshes)
            assertEquals(emptyList<UiText>(), followUp.finished(emptyList()))

            assertEquals(localId, resolveRemote())
            assertEquals(1, refreshes)
        }

    @Test
    fun `follow-up leaves a file that already existed unlinked`() =
        runBlocking {
            val localId = resolveLocal()
            val followUp = copies.followUp()

            followUp.uploaded(CREDENTIALS, uploadedFile(fileId = null))
            followUp.finished(emptyList())

            assertNotEquals(localId, resolveRemote())
            assertEquals("the file is on the server either way", 1, refreshes)
        }

    @Test
    fun `follow-up without files on the server leaves the library alone`() =
        runBlocking {
            copies.followUp().finished(listOf(SELECTION))

            assertEquals(0, refreshes)
        }

    private fun uploadedFile(fileId: Long?) =
        UploadedFile(
            selection = SELECTION,
            url = "http://test.example/remote.php/dav/files/test/Photos/a.jpg".toHttpUrl(),
            created = fileId != null,
            fileId = fileId,
        )

    private suspend fun resolveLocal(): MediaId {
        val source = MediaSourceIdentity(MediaSourceKind.Local, CONTENT_URI)
        val candidate = mediaIdentityCandidate(source, auid = LOCAL_AUID, buid = LOCAL_BUID)
        return registry.resolve(listOf(candidate)).mediaIds.getValue(source)
    }

    /** Without explicit aliases the server copy matches nothing. */
    private suspend fun resolveRemote(
        fileId: Long = FILE_ID,
        auid: String? = null,
        buid: String? = null,
    ): MediaId {
        val source = MediaSourceIdentity(MediaSourceKind.Memories, fileId.toString())
        val candidate = mediaIdentityCandidate(source, auid = auid, buid = buid)
        return registry.resolve(listOf(candidate)).mediaIds.getValue(source)
    }

    private fun localItem(mediaId: MediaId) =
        item(mediaId, LOCAL_AUID, LOCAL_BUID, MediaAssetRef.LocalContent(CONTENT_URI, TAKEN_AT))

    private fun remoteItem(mediaId: MediaId, fileId: Long = FILE_ID, auid: String? = null) =
        item(mediaId, auid, buid = null, MediaAssetRef.MemoriesFile(fileId))

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
        val SELECTION = UploadSelection(CONTENT_URI, "a.jpg", "image/jpeg")
        const val FILE_ID = 183L
        const val SECOND_FILE_ID = 190L
        const val TAKEN_AT = 1_716_900_000L
        const val LOCAL_AUID = "local-auid"
        const val LOCAL_BUID = "local-buid"
    }
}

private object SignedInStore : CredentialsStore {
    override fun load(): AccountCredentials = CREDENTIALS

    override fun save(credentials: AccountCredentials) = Unit

    override fun clear() = Unit
}

private val CREDENTIALS = AccountCredentials("http://test.example", "test", "app-password", "test")

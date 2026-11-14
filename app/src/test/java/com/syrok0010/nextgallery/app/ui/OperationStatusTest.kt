package com.syrok0010.nextgallery.app.ui

import androidx.compose.runtime.mutableStateOf
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.operation.OperationKind
import com.syrok0010.nextgallery.core.operation.OperationMediaActivity
import com.syrok0010.nextgallery.core.operation.OperationMediaReference
import com.syrok0010.nextgallery.core.operation.OperationStatusSnapshot
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.collection.MediaTileActivity
import com.syrok0010.nextgallery.feature.collection.MediaTileOperation
import com.syrok0010.nextgallery.feature.upload.UploadJob
import com.syrok0010.nextgallery.feature.upload.UploadSelection
import com.syrok0010.nextgallery.feature.upload.uploadActivity
import com.syrok0010.nextgallery.feature.upload.uploadOperationSnapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationStatusTest {
    private val snapshots = mutableStateOf(emptyList<OperationStatusSnapshot>())
    private val status = OperationStatus(emptyList(), snapshots)

    /** A photo with a phone copy and a cloud copy, as the timeline shows it. */
    private val photo =
        item(MediaAssetRef.LocalFirst(local(PHONE_URI), MediaAssetRef.MemoriesFile(FILE_ID)))

    @Test fun tileShowsDeletionOverUploadOverAlbum() {
        val delete = running(OperationKind.Delete, OperationMediaReference.RemoteFileId(FILE_ID))
        val upload = running(OperationKind.Upload, OperationMediaReference.LocalUri(PHONE_URI))
        val album = running(OperationKind.AddToAlbum, OperationMediaReference.RemoteFileId(FILE_ID))

        snapshots.value = listOf(album, upload, delete)
        assertEquals(MediaTileOperation.Delete, status.tileActivity(photo)?.operation)

        snapshots.value = listOf(album, upload)
        assertEquals(MediaTileOperation.Upload, status.tileActivity(photo)?.operation)

        snapshots.value = listOf(album)
        assertEquals(MediaTileOperation.AddToAlbum, status.tileActivity(photo)?.operation)
    }

    @Test fun operationOnAnotherCopyLeavesTheTileAlone() {
        snapshots.value = listOf(
            running(OperationKind.Delete, OperationMediaReference.RemoteFileId(FILE_ID + 1)),
            running(
                OperationKind.Upload,
                OperationMediaReference.LocalUri("content://media/external/images/media/99"),
            ),
        )

        assertNull(status.tileActivity(photo))
        // A cloud-only tile has no phone URI to match an upload by.
        assertNull(status.tileActivity(item(MediaAssetRef.MemoriesFile(FILE_ID + 2))))
    }

    @Test fun uploadPickedThroughAVolumeUriMarksTheIndexedPhoneTile() {
        // The picker may name the primary volume; the library indexes the same file as `external`.
        val job = UploadJob(
            id = "job",
            selection = UploadSelection(
                local("content://media/external_primary/images/media/5"),
                "a.jpg",
                "image/jpeg",
            ),
        )
        snapshots.value = uploadOperationSnapshots(
            uploadActivity(listOf(job), setOf("job"), progress = null),
            result = null,
        )

        assertEquals(
            MediaTileActivity(MediaTileOperation.Upload, progress = 0f),
            status.tileActivity(item(local(PHONE_URI))),
        )
    }

    @Test fun onlyRunningWorkBlocksLogout() {
        var loggedOut = 0
        snapshots.value = listOf(
            running(OperationKind.Upload, OperationMediaReference.LocalUri(PHONE_URI)),
        )
        assertFalse(status.tryLogout { loggedOut++ })

        // A result waiting to be read does not.
        snapshots.value = listOf(
            OperationStatusSnapshot(OperationKind.Upload, uiText(0), failed = true),
        )
        assertTrue(status.active)
        assertTrue(status.tryLogout { loggedOut++ })
        assertEquals(1, loggedOut)
    }

    private fun running(kind: OperationKind, reference: OperationMediaReference) =
        OperationStatusSnapshot(
            kind,
            uiText(0),
            running = true,
            media = listOf(OperationMediaActivity(reference)),
        )

    private fun local(uri: String) = MediaAssetRef.LocalContent(uri, 1, 1)

    private fun item(asset: MediaAssetRef) =
        MediaItem(
            mediaId = MediaId("photo"),
            dayId = 1,
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            width = null,
            height = null,
            etag = null,
            livePhotoId = null,
            auid = null,
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = 1,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = asset,
        )

    private companion object {
        const val PHONE_URI = "content://media/external/images/media/5"
        const val FILE_ID = 42L
    }
}

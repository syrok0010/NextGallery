package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.*
import org.junit.Assert.*
import org.junit.Test

class UploadQueueTest {
    @Test fun `only local-only selections are uploaded and keep both URI roles`() {
        assertNull(uploadSelection(item("remote", MediaAssetRef.MemoriesFile(1))))
        assertNull(
            uploadSelection(
                item(
                    "merged",
                    MediaAssetRef.LocalFirst(local("merged"), MediaAssetRef.MemoriesFile(2)),
                ),
            ),
        )
        val selected = uploadSelection(item("local", local("external_primary/images/media/42")))!!
        assertEquals("content://media/external_primary/images/media/42", selected.contentUri)
        assertEquals("content://media/external/images/media/42", selected.identityUri)
        assertEquals("local", selected.mediaId)
    }
    private fun local(name: String) = MediaAssetRef.LocalContent("content://media/$name", 1)

    private fun item(name: String, asset: MediaAssetRef) =
        MediaItem(
            mediaId = MediaId(name),
            dayId = 1,
            displayName = "$name.jpg",
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
}

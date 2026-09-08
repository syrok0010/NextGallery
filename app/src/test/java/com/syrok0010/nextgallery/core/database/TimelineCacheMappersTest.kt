package com.syrok0010.nextgallery.core.database

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.timeline.persistence.toMediaItem
import com.syrok0010.nextgallery.feature.timeline.persistence.toMemoriesMediaEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineCacheMappersTest {
    @Test
    fun `cache mapping restores memories asset reference from file identity`() {
        val item = MediaItem(
            mediaId = MediaId("persistent-media-id"),
            dayId = 19870,
            displayName = "IMG_0042.jpg",
            mimeType = "image/jpeg",
            width = 4032,
            height = 3024,
            etag = "etag-42",
            livePhotoId = null,
            auid = "auid-42",
            buid = "buid-42",
            sharedBy = null,
            takenAtEpochSeconds = 1_717_100_000L,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = true,
            isHidden = false,
            assetRef = MediaAssetRef.MemoriesFile(photoFileId = 42L),
        )

        val entity = item.toMemoriesMediaEntity()
        val restored = IdentifiedMemoriesMedia(
            media = entity,
            mediaId = item.mediaId.value,
        ).toMediaItem()

        assertEquals(42L, entity.fileId)
        assertEquals(item, restored)
    }
}

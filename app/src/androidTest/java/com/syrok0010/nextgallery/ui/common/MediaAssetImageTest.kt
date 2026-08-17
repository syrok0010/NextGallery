package com.syrok0010.nextgallery.ui.common

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.thumbnail.coilCacheKey
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAssetImageTest {
    @Test
    fun localOriginalUsesTimelineThumbnailAsCachedPlaceholder() {
        val assetRef = MediaAssetRef.LocalContent(
            contentUri = "content://media/external/images/media/42",
            modifiedAtEpochSeconds = 1_700_000_000,
        )
        val requests = mediaImageRequests(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            item = mediaItem(assetRef),
            credentials = AccountCredentials(
                serverUrl = "https://cloud.example.com/",
                loginName = "user",
                appPassword = "secret",
            ),
            purpose = MediaImagePurpose.Original,
        )

        assertEquals(1, requests.size)
        assertEquals(
            "${assetRef.coilCacheKey()}:${MediaImagePurpose.Original}",
            requests.single().memoryCacheKey,
        )
        assertEquals(
            "${assetRef.coilCacheKey()}:${MediaImagePurpose.TimelineThumbnail}",
            requests.single().placeholderMemoryCacheKey?.key,
        )
    }

    private fun mediaItem(assetRef: MediaAssetRef): MediaItem = MediaItem(
        mediaId = MediaId("local:42"),
        remoteFileId = null,
        dayId = 1,
        day = LocalDate.of(2026, 8, 17),
        displayName = "local.jpg",
        mimeType = "image/jpeg",
        width = 4000,
        height = 3000,
        etag = null,
        livePhotoId = null,
        auid = null,
        buid = null,
        sharedBy = null,
        takenAtEpochSeconds = null,
        isVideo = false,
        videoDurationSeconds = null,
        isFavorite = false,
        isHidden = false,
        assetRef = assetRef,
    )
}

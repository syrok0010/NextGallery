package com.syrok0010.nextgallery.ui.common

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.thumbnail.coilCacheKey
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAssetImageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun localOriginalUsesTimelineThumbnailAsCachedPlaceholder() {
        val assetRef = MediaAssetRef.LocalContent(
            contentUri = "content://media/external/images/media/42",
            modifiedAtEpochSeconds = 1_700_000_000,
        )
        val plan = mediaImageRequestPlan(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            item = mediaItem(assetRef),
            credentials = AccountCredentials(
                serverUrl = "https://cloud.example.com/",
                loginName = "user",
                appPassword = "secret",
            ),
            purpose = MediaImagePurpose.Original,
        )

        assertNull(plan.preview)
        assertNull(plan.fallback)
        assertEquals(
            "${assetRef.coilCacheKey()}:${MediaImagePurpose.Original}",
            plan.primary.memoryCacheKey,
        )
        assertEquals(
            "${assetRef.coilCacheKey()}:${MediaImagePurpose.TimelineThumbnail}",
            plan.primary.placeholderMemoryCacheKey?.key,
        )
    }

    @Test
    fun unifiedOriginalTriesLocalContentBeforeCloudStream() {
        val local = MediaAssetRef.LocalContent(
            contentUri = "content://media/external/images/media/42",
            modifiedAtEpochSeconds = 1_700_000_000,
        )
        val plan = mediaImageRequestPlan(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            item = mediaItem(
                MediaAssetRef.LocalFirst(
                    local = local,
                    remote = MediaAssetRef.MemoriesFile(photoFileId = 42),
                ),
            ),
            credentials = AccountCredentials(
                serverUrl = "https://cloud.example.com/",
                loginName = "user",
                appPassword = "secret",
            ),
            purpose = MediaImagePurpose.Original,
        )

        assertEquals(local.contentUri, plan.primary.data)
        assertEquals("https://cloud.example.com/apps/memories/api/stream/42", plan.fallback?.data)
    }

    @Test
    fun unifiedDetailDoesNotLoadRemotePreviewWhileLocalContentIsAvailable() {
        val local = MediaAssetRef.LocalContent(
            contentUri = "content://media/external/images/media/42",
            modifiedAtEpochSeconds = 1_700_000_000,
        )
        val plan = mediaImageRequestPlan(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            item = mediaItem(MediaAssetRef.LocalFirst(local, MediaAssetRef.MemoriesFile(42))),
            credentials = AccountCredentials(
                serverUrl = "https://cloud.example.com/",
                loginName = "user",
                appPassword = "secret",
            ),
            purpose = MediaImagePurpose.DetailPreview,
        )

        assertEquals(local.contentUri, plan.primary.data)
        assertNull(plan.preview)
        assertEquals(
            "https://cloud.example.com/apps/memories/api/image/preview/42?x=1600&y=1600&a=1",
            plan.fallback?.data,
        )
    }

    @Test
    fun projectedMediaFallsBackToCloudAfterLocalReadErrorWithoutChangingMediaId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val localAsset = MediaAssetRef.LocalContent(
            contentUri = "content://missing/images/42",
            modifiedAtEpochSeconds = 1_700_000_000,
        )
        val local = mediaItem(localAsset).copy(
            mediaId = MediaId("published-local"),
            auid = "shared-auid",
        )
        val remote = mediaItem(MediaAssetRef.MemoriesFile(42)).copy(
            mediaId = MediaId("generated-remote"),
            remoteFileId = 42,
            auid = "shared-auid",
        )
        val item = remote.copy(
            mediaId = local.mediaId,
            assetRef = MediaAssetRef.LocalFirst(
                local = localAsset,
                remote = remote.assetRef as MediaAssetRef.MemoriesFile,
            ),
        )
        val requestedData = CopyOnWriteArrayList<Any>()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val imageLoader = ImageLoader.Builder(context)
            .components {
                add(
                    Interceptor { chain ->
                        requestedData += chain.request.data
                        if (chain.request.data == localAsset.contentUri) {
                            ErrorResult(null, chain.request, IllegalStateException("Local URI is unreadable"))
                        } else {
                            SuccessResult(bitmap.asImage(), chain.request)
                        }
                    },
                )
            }
            .build()

        try {
            composeRule.setContent {
                MediaAssetImage(
                    item = item,
                    credentials = credentials,
                    purpose = MediaImagePurpose.Original,
                    contentDescription = null,
                    imageLoader = imageLoader,
                )
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { requestedData.size >= 2 }

            assertEquals(MediaId("published-local"), item.mediaId)
            assertEquals(
                listOf(
                    localAsset.contentUri,
                    "https://cloud.example.com/apps/memories/api/stream/42",
                ),
                requestedData.take(2),
            )
        } finally {
            imageLoader.shutdown()
            bitmap.recycle()
        }
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

    private companion object {
        val credentials = AccountCredentials(
            serverUrl = "https://cloud.example.com/",
            loginName = "user",
            appPassword = "secret",
        )
    }
}

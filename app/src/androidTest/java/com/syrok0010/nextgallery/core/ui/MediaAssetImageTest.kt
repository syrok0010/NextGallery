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
import com.syrok0010.nextgallery.data.credentials.CredentialsStore
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.domain.media.MediaId
import com.syrok0010.nextgallery.ui.SessionStore
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAssetImageTest {
    @get:Rule
    val composeRule = createComposeRule()

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
                    purpose = MediaImagePurpose.Original,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    requestFactory = requestFactory(),
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

    private fun requestFactory(): MediaImageRequestFactory = MediaImageRequestFactory(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        sessionStore = sessionStore(credentials),
    )

    private fun sessionStore(initialCredentials: AccountCredentials): SessionStore =
        SessionStore(
            object : CredentialsStore {
                override fun load(): AccountCredentials = initialCredentials
                override fun save(credentials: AccountCredentials) = Unit
                override fun clear() = Unit
            },
        )

    private companion object {
        val credentials = AccountCredentials(
            serverUrl = "https://cloud.example.com/",
            loginName = "user",
            appPassword = "secret",
        )
    }
}

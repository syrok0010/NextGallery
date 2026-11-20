package com.syrok0010.nextgallery.feature.images

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalThumbnailQualityTest {
    @Test fun videoStillUsesThePlatformThumbnail() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "thumbnail-quality-video.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/thumbnail-quality-test")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }))
        try {
            instrumentation.context.assets.open("local-video.mp4").use { input ->
                resolver.openOutputStream(uri)!!.use { input.copyTo(it) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            val result = LocalThumbnailFetcher(
                LocalThumbnailRequest(uri.toString(), "quality-video"),
                Options(context, size = Size(256, 256), scale = Scale.FILL),
            ).fetch() as ImageFetchResult
            val thumbnail = (result.image as BitmapImage).bitmap
            try { assertTrue(thumbnail.width > 0 && thumbnail.height > 0) } finally { thumbnail.recycle() }
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun longImagesKeepCentralDetailsAtTheTileResolution() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        for ((width, height) in listOf(256 to 4096, 4096 to 256, 1024 to 768)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            for (x in 0 until width step 2) {
                paint.color = if (x % 4 == 0) Color.BLACK else Color.WHITE
                canvas.drawRect(x.toFloat(), 0f, (x + 2).toFloat(), height.toFloat(), paint)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "thumbnail-quality-$width-$height.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/thumbnail-quality-test")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            try {
                resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                val result = LocalThumbnailFetcher(
                    LocalThumbnailRequest(uri.toString(), "quality-$width-$height"),
                    Options(context, size = Size(256, 256), scale = Scale.FILL),
                ).fetch() as ImageFetchResult
                val thumbnail = (result.image as BitmapImage).bitmap
                try {
                    assertTrue("$width x $height returned ${thumbnail.width} x ${thumbnail.height}", thumbnail.width >= 256 && thumbnail.height >= 256)
                    assertTrue("Output must stay bounded", thumbnail.allocationByteCount <= 256 * 256 * 8)
                    if (width == 256) {
                        assertTrue("Fine center stripes must retain contrast", Color.red(thumbnail.getPixel(128, 128)) < 30)
                        assertTrue("Fine center stripes must retain contrast", Color.red(thumbnail.getPixel(130, 128)) > 225)
                    }
                } finally { thumbnail.recycle() }
            } finally { resolver.delete(uri, null, null) }
        }
    }
}

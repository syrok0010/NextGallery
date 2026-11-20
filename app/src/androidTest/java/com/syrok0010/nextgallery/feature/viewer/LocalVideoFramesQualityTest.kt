package com.syrok0010.nextgallery.feature.viewer

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVideoFramesQualityTest {
    @Test fun framesCoverTheCellAtTripleDensity() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext.createConfigurationContext(
                Configuration(
                    instrumentation.targetContext.resources.configuration,
                ).apply { densityDpi = 480 },
            )
            for (name in listOf("portrait", "landscape", "rotated")) {
                val file = File(context.cacheDir, "filmstrip-$name.mp4")
                try {
                    instrumentation.context.assets.open("filmstrip/$name.mp4").use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    val frame = LocalVideoFrames(context)
                        .frames(file.toURI().toString())
                        .filterIsInstance<VideoFrameEvent.Frame>()
                        .first()
                        .bitmap
                    try {
                        assertTrue(
                            "$name frame ${frame.width}x${frame.height} needs 177x207 px",
                            frame.width >= 177 && frame.height >= 207,
                        )
                        assertTrue(
                            "Keep the frame bounded after cropping",
                            frame.allocationByteCount <= 177 * 207 * 8,
                        )
                    } finally {
                        frame.recycle()
                    }
                } finally {
                    file.delete()
                }
            }
        }
}

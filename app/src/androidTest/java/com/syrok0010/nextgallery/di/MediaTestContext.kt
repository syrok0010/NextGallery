package com.syrok0010.nextgallery.di

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import com.syrok0010.nextgallery.feature.viewer.VideoFramesFactory
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import kotlinx.serialization.json.Json
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** Leaf UI tests need media adapters, without starting production indexers or background work. */
@Composable
internal fun MediaTestContext(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val application = remember(context) {
        koinApplication {
            modules(module {
                single { AccountCredentials("http://127.0.0.1:9", "fixture", "unused", "fixture") }
                single { VideoPlayerFactory(NextcloudTransport(Json), get()) }
                single { VideoFramesFactory(context, get()) }
                single { MediaImageRequestFactory(context, get()) }
                single { ImageLoader.Builder(context).build() }
            })
        }
    }
    DisposableEffect(application) {
        onDispose {
            application.koin.get<ImageLoader>().shutdown()
            application.close()
        }
    }
    KoinIsolatedContext(application, content = content)
}

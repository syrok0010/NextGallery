package com.syrok0010.nextgallery.feature.images

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class LocalMediaRequestInterceptor : Interceptor {
    private val permits = Semaphore(PERMITS)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (chain.request.data is LocalThumbnailRequest) {
            return permits.withPermit { chain.proceed() }
        }

        val uri = when (val data = chain.request.data) {
            is String -> data
            is android.net.Uri -> data.toString()
            is coil3.Uri -> data.toString()
            else -> return chain.proceed()
        }
        if (!uri.startsWith("content://media/")) return chain.proceed()

        return permits.withPermit { chain.proceed() }
    }

    companion object {
        /** Local opens and decodes at once; the rest wait without touching the file. */
        const val PERMITS = 8
    }
}

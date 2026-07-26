package com.syrok0010.nextgallery.data.network

import android.content.Context
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.syrok0010.nextgallery.data.auth.NextcloudAuthApi
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MemoriesApi
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class NextcloudTransport(
    private val json: Json,
) {
    private val publicClient = baseClientBuilder()
        .build()

    fun nextcloudAuthApi(serverUrl: String): NextcloudAuthApi {
        return retrofit(
            baseUrl = normalizeBaseUrl(serverUrl),
            client = publicClient,
        ).create(NextcloudAuthApi::class.java)
    }

    fun memoriesApi(credentials: AccountCredentials): MemoriesApi {
        return retrofit(
            baseUrl = normalizeBaseUrl(credentials.serverUrl),
            client = authenticatedClient(credentials),
        ).create(MemoriesApi::class.java)
    }

    fun normalizeBaseUrl(input: String): String {
        return normalizeServerOrigin(input) + "/"
    }

    fun authenticatedClient(credentials: AccountCredentials): OkHttpClient {
        return baseClientBuilder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val request = applyAuthenticatedHeaders(
                    builder = originalRequest.newBuilder(),
                    credentials = credentials,
                    accept = originalRequest.header("Accept") ?: "application/json",
                ).build()
                chain.proceed(request)
            }
            .build()
    }

    fun authenticatedRequestBuilder(
        credentials: AccountCredentials,
        url: String,
        accept: String? = null,
    ): Request.Builder {
        return applyAuthenticatedHeaders(
            builder = Request.Builder().url(url),
            credentials = credentials,
            accept = accept,
        )
    }

    companion object {
        fun normalizeServerOrigin(input: String): String {
            val trimmed = input.trim()
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }

            return withScheme.trimEnd('/')
        }

        fun authenticatedImageRequest(
            context: Context,
            url: String,
            credentials: AccountCredentials,
        ): ImageRequest {
            return ImageRequest.Builder(context)
                .data(url)
                .httpHeaders(authenticatedNetworkHeaders(credentials))
                .build()
        }

        internal fun applyAuthenticatedHeaders(
            builder: Request.Builder,
            credentials: AccountCredentials,
            accept: String? = null,
        ): Request.Builder {
            return builder
                .header("Authorization", authorizationHeader(credentials))
                .header("Accept", accept ?: "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("OCS-APIRequest", "true")
        }

        internal fun authenticatedNetworkHeaders(credentials: AccountCredentials): NetworkHeaders {
            return NetworkHeaders.Builder()
                .set("Authorization", authorizationHeader(credentials))
                .set("X-Requested-With", "XMLHttpRequest")
                .set("OCS-APIRequest", "true")
                .build()
        }

        internal fun authorizationHeader(credentials: AccountCredentials): String {
            return Credentials.basic(credentials.loginName, credentials.appPassword)
        }
    }

    private fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    private fun baseClientBuilder(): OkHttpClient.Builder {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                    .newBuilder()
                    .header("User-Agent", "NextGallery/0.1 Android")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
    }
}

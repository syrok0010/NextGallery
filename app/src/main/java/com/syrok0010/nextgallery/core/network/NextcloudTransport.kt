package com.syrok0010.nextgallery.core.network

import com.syrok0010.nextgallery.core.session.AccountCredentials
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
    internal val baseClient: OkHttpClient = defaultBaseClient(),
) {

    fun normalizeBaseUrl(input: String): String = normalizeServerOrigin(input) + "/"

    fun authenticatedClient(credentials: AccountCredentials): OkHttpClient =
        baseClient
            .newBuilder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val request = applyAuthenticatedHeaders(
                    builder = originalRequest.newBuilder(),
                    credentials = credentials,
                    accept = originalRequest.header("Accept") ?: "application/json",
                ).build()
                chain.proceed(request)
            }.build()

    fun authenticatedRequestBuilder(
        credentials: AccountCredentials,
        url: String,
        accept: String? = null,
    ): Request.Builder =
        applyAuthenticatedHeaders(
            builder = Request.Builder().url(url),
            credentials = credentials,
            accept = accept,
        )

    companion object {
        fun defaultBaseClient(): OkHttpClient {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            return OkHttpClient
                .Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain
                        .request()
                        .newBuilder()
                        .header("User-Agent", "NextGallery/0.1 Android")
                        .build()
                    chain.proceed(request)
                }.addInterceptor(logging)
                .build()
        }

        fun normalizeServerOrigin(input: String): String {
            val trimmed = input.trim()
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }

            return withScheme.trimEnd('/')
        }

        internal fun applyAuthenticatedHeaders(
            builder: Request.Builder,
            credentials: AccountCredentials,
            accept: String? = null,
        ): Request.Builder =
            builder
                .header("Authorization", authorizationHeader(credentials))
                .header("Accept", accept ?: "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("OCS-APIRequest", "true")

        internal fun authorizationHeader(credentials: AccountCredentials): String =
            Credentials.basic(credentials.loginName, credentials.appPassword)
    }

    fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
}

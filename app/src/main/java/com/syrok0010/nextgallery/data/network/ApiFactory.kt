package com.syrok0010.nextgallery.data.network

import com.syrok0010.nextgallery.data.auth.NextcloudAuthApi
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MemoriesApi
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class ApiFactory(
    private val json: Json,
) {
    private val publicClient = baseClientBuilder()
        .build()

    fun nextcloudAuthApi(serverUrl: String): NextcloudAuthApi {
        return retrofit(
            baseUrl = normalizeServerUrl(serverUrl),
            client = publicClient,
        ).create(NextcloudAuthApi::class.java)
    }

    fun memoriesApi(credentials: AccountCredentials): MemoriesApi {
        return retrofit(
            baseUrl = normalizeServerUrl(credentials.serverUrl),
            client = authenticatedClient(credentials),
        ).create(MemoriesApi::class.java)
    }

    fun normalizeServerUrl(input: String): String {
        val trimmed = input.trim()
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        return withScheme.trimEnd('/') + "/"
    }

    private fun authenticatedClient(credentials: AccountCredentials): OkHttpClient {
        val authorization = Credentials.basic(credentials.loginName, credentials.appPassword)

        return baseClientBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                    .newBuilder()
                    .header("Authorization", authorization)
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("OCS-APIRequest", "true")
                    .build()
                chain.proceed(request)
            }
            .build()
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

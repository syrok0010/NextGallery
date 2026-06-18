package com.syrok0010.nextgallery.di

import com.syrok0010.nextgallery.data.auth.NextcloudLoginRepository
import com.syrok0010.nextgallery.data.credentials.CredentialsStore
import com.syrok0010.nextgallery.data.credentials.KeystoreCredentialsStore
import com.syrok0010.nextgallery.data.memories.MemoriesMultipreviewClient
import com.syrok0010.nextgallery.data.memories.MemoriesRepository
import com.syrok0010.nextgallery.data.network.ApiFactory
import com.syrok0010.nextgallery.ui.MainViewModel
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }

    single { ApiFactory(get()) }
    single<CredentialsStore> { KeystoreCredentialsStore(androidContext(), get()) }
    single { NextcloudLoginRepository(get()) }
    single { MemoriesMultipreviewClient(get(), get()) }
    single { MemoriesRepository(get(), get()) }

    viewModelOf(::MainViewModel)
}

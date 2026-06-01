package com.syrok0010.nextgallery.di

import com.syrok0010.nextgallery.data.auth.NextcloudLoginRepository
import com.syrok0010.nextgallery.data.credentials.CredentialsStore
import com.syrok0010.nextgallery.data.credentials.SharedPreferencesCredentialsStore
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
    single<CredentialsStore> { SharedPreferencesCredentialsStore(androidContext()) }
    single { NextcloudLoginRepository(get()) }
    single { MemoriesRepository(get()) }

    viewModelOf(::MainViewModel)
}

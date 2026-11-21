package com.syrok0010.nextgallery.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import com.syrok0010.nextgallery.feature.auth.*
import org.koin.compose.KoinApplicationPreview
import org.koin.dsl.module

private fun loginPreviewModule() =
    module {
        single<CredentialsStore> {
            object : CredentialsStore {
                override fun load(): AccountCredentials? = null
                override fun save(credentials: AccountCredentials) = Unit
            }
        }
        single { SessionStore(get()) }
        single<LoginGateway> {
            object : LoginGateway {
                override suspend fun checkMemories(credentials: AccountCredentials) =
                    MemoriesCompatibility.NetworkError
                override suspend fun startLogin(serverUrl: String): LoginSession =
                    error("Preview has no network")
                override suspend fun pollLogin(session: LoginSession) = LoginPollResult.Pending
            }
        }
        factory { LoginViewModel(get(), get(), get()) }
    }

@Preview(showBackground = true)
@Composable
private fun LoginPreview() {
    KoinApplicationPreview(application = { modules(loginPreviewModule()) }) {
        NextGalleryTheme { LoginScreen() }
    }
}

@Preview(showBackground = true, locale = "ru")
@Composable
private fun LoginServerPreview() {
    KoinApplicationPreview(application = { modules(loginPreviewModule()) }) {
        val model = org.koin.compose.koinInject<LoginViewModel>()
        androidx.compose.runtime.LaunchedEffect(model) {
            model.updateServerUrl("https://cloud.example.org")
        }
        NextGalleryTheme { LoginScreen(model) }
    }
}

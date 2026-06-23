package com.syrok0010.nextgallery.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.ui.NextGalleryScaffold
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun LoginScreen(viewModel: LoginViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()

    NextGalleryScaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LoginPanel(
                state = state.login,
                message = state.message,
                isBusy = state.isBusy,
                onServerUrlChange = viewModel::updateServerUrl,
                onStartLogin = viewModel::startLogin,
                onLoginBrowserOpened = viewModel::markLoginBrowserOpened,
                onLoginBrowserOpenFailed = viewModel::reportLoginBrowserOpenFailure,
                onCancelLogin = viewModel::cancelLogin,
            )

            if (state.isBusy || state.login.isPolling) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(28.dp),
                )
            }
        }
    }
}

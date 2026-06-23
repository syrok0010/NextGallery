package com.syrok0010.nextgallery.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.ui.AppMessageUiState
import com.syrok0010.nextgallery.ui.NextGalleryScaffold
import com.syrok0010.nextgallery.ui.SessionUiState

@Composable
internal fun LoginDestinationScreen(
    session: SessionUiState.SignedOut?,
    message: AppMessageUiState,
    isBusy: Boolean,
    onServerUrlChange: (String) -> Unit,
    onStartLogin: () -> Unit,
    onLoginBrowserOpened: () -> Unit,
    onLoginBrowserOpenFailed: () -> Unit,
    onCancelLogin: () -> Unit,
) {
    NextGalleryScaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (session != null) {
                LoginPanel(
                    state = session.login,
                    message = message,
                    isBusy = isBusy,
                    onServerUrlChange = onServerUrlChange,
                    onStartLogin = onStartLogin,
                    onLoginBrowserOpened = onLoginBrowserOpened,
                    onLoginBrowserOpenFailed = onLoginBrowserOpenFailed,
                    onCancelLogin = onCancelLogin,
                )
            }

            if (isBusy || session?.login?.isPolling == true) {
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

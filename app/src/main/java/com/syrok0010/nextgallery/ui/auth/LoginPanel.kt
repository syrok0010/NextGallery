package com.syrok0010.nextgallery.ui.auth

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.ui.AppMessageUiState
import com.syrok0010.nextgallery.ui.LoginUiState
import com.syrok0010.nextgallery.ui.common.StatusBlock

@Composable
internal fun LoginPanel(
    state: LoginUiState,
    message: AppMessageUiState,
    isBusy: Boolean,
    onServerUrlChange: (String) -> Unit,
    onStartLogin: () -> Unit,
    onLoginBrowserOpened: () -> Unit,
    onLoginBrowserOpenFailed: () -> Unit,
    onCancelLogin: () -> Unit,
) {
    val context = LocalContext.current
    val session = state.session
    val openLoginUrl: (String) -> Unit = { loginUrl ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, loginUrl.toUri()))
        }.onSuccess {
            onLoginBrowserOpened()
        }.onFailure {
            onLoginBrowserOpenFailed()
        }
    }

    val loginUrlToOpen = session?.loginUrl?.takeUnless { state.browserOpened }
    LaunchedEffect(loginUrlToOpen) {
        if (loginUrlToOpen != null) {
            openLoginUrl(loginUrlToOpen)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = state.serverUrlInput,
            onValueChange = onServerUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.login_server_url_label)) },
            placeholder = { Text(stringResource(R.string.login_server_url_placeholder)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStartLogin,
                enabled = !isBusy,
            ) {
                Text(
                    stringResource(
                        if (session == null) {
                            R.string.action_start_login
                        } else {
                            R.string.action_restart_login
                        },
                    ),
                )
            }

            if (session != null) {
                Button(
                    onClick = { openLoginUrl(session.loginUrl) },
                    enabled = !isBusy,
                ) {
                    Text(stringResource(R.string.action_open_browser))
                }
            }
        }

        if (session != null) {
            TextButton(
                onClick = onCancelLogin,
                enabled = !isBusy,
            ) {
                Text(stringResource(R.string.action_cancel_login))
            }
        }

        StatusBlock(message)
    }
}

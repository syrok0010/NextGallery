package com.syrok0010.nextgallery.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.syrok0010.nextgallery.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NextGalleryScaffold(
    showTopBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            Box(modifier = Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = {},
                    actions = {},
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        navigationIconContentColor = Color.Transparent,
                        titleContentColor = Color.Transparent,
                        actionIconContentColor = Color.Transparent,
                    ),
                )

                AnimatedVisibility(
                    visible = showTopBar,
                    enter = fadeIn(animationSpec = tween(durationMillis = TopBarFadeDurationMillis)),
                    exit = fadeOut(animationSpec = tween(durationMillis = TopBarFadeDurationMillis)),
                ) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name)) },
                        actions = actions,
                    )
                }
            }
        },
        content = content,
    )
}

private const val TopBarFadeDurationMillis = 180

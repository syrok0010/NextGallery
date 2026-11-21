package com.syrok0010.nextgallery

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.syrok0010.nextgallery.app.ui.NextGalleryApp
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import com.syrok0010.nextgallery.router.AppNavigator
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val navigator: AppNavigator by inject()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let(navigator::follow)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity gets its old intent again; that link was already followed.
        if (savedInstanceState == null) intent.data?.let(navigator::follow)
        enableEdgeToEdge()
        setContent {
            NextGalleryTheme {
                NextGalleryApp()
            }
        }
    }
}

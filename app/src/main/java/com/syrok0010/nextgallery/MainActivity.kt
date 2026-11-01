package com.syrok0010.nextgallery

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.syrok0010.nextgallery.app.ui.NextGalleryApp
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import com.syrok0010.nextgallery.feature.upload.UploadForegroundService

class MainActivity : ComponentActivity() {
    private val openUploads = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openUploads.value = intent.action == UploadForegroundService.ACTION_UPLOADS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openUploads.value = intent.action == UploadForegroundService.ACTION_UPLOADS
        enableEdgeToEdge()
        setContent {
            NextGalleryTheme {
                NextGalleryApp(
                    openUploads = openUploads.value,
                    onUploadsOpened = {
                        openUploads.value = false
                        intent.action = null
                    },
                )
            }
        }
    }
}

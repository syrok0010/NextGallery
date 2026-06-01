package com.syrok0010.nextgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.syrok0010.nextgallery.ui.NextGalleryApp
import com.syrok0010.nextgallery.ui.theme.NextGalleryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NextGalleryTheme {
                NextGalleryApp()
            }
        }
    }
}

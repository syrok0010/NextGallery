package com.syrok0010.nextgallery

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.syrok0010.nextgallery.app.ui.NextGalleryApp
import com.syrok0010.nextgallery.core.locale.withAppLanguage
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(
            Configuration().apply {
                setLocales(newBase.resources.configuration.withAppLanguage().locales)
            },
        )
    }

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

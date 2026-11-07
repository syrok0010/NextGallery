package com.syrok0010.nextgallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.syrok0010.nextgallery.app.ui.NextGalleryApp
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    // Holds a link until the app follows it, e.g. one that arrives before sign-in.
    private val links = Channel<Uri>(Channel.CONFLATED)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let(links::trySend)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity gets its old intent again; that link was already followed.
        if (savedInstanceState == null) intent.data?.let(links::trySend)
        val linkFlow = links.receiveAsFlow()
        enableEdgeToEdge()
        setContent {
            NextGalleryTheme {
                NextGalleryApp(links = linkFlow)
            }
        }
    }
}

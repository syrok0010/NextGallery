package com.syrok0010.nextgallery.app

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.feature.auth.LoginViewModel
import com.syrok0010.nextgallery.feature.timeline.AuthenticatedViewModel
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class AppCompositionTest {
    @Test fun featureEntryPointsResolveFromProductionGraph() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val koin = GlobalContext.get()
            val store = ViewModelStore()
            try {
                store.put("auth", koin.get<LoginViewModel>())
                store.put("timeline", koin.get<AuthenticatedViewModel>())
                koin.get<MediaImageRequestFactory>()
            } finally {
                store.clear()
            }
        }
    }
}

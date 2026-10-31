package com.syrok0010.nextgallery.feature.deletion

import android.app.Activity
import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.feature.collection.MediaSelection
import com.syrok0010.nextgallery.feature.collection.rememberMediaSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import com.syrok0010.nextgallery.app.ui.OperationStatusPill
import com.syrok0010.nextgallery.app.ui.rememberOperationStatus
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import com.syrok0010.nextgallery.feature.upload.UploadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test

class DeleteActionRestorationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun confirmedResultClearsRestoredSelection() = checkRestoredResult(Activity.RESULT_OK)

    @Test fun cancelledResultKeepsRestoredSelection() =
        checkRestoredResult(
            Activity.RESULT_CANCELED,
        )

    private fun checkRestoredResult(resultCode: Int) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = requireNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "delete-restoration.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ngs135-test/")
                },
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val uploads = UploadQueue(SessionStore(object : CredentialsStore {
            override fun load() = AccountCredentials("http://127.0.0.1:1", "test", "unused", "test")
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        }), scope)
        try {
            val registry = TrashRegistry()
            val owner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
            val restoration = StateRestorationTester(rule)
            lateinit var selection: MediaSelection
            lateinit var delete: DeleteAction
            val confirmed = mutableListOf<DeletionTargets>()
            restoration.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    val currentSelection = rememberMediaSelection()
                    val action = rememberDeleteAction(confirmed::add, currentSelection::clear)
                    val operations = rememberOperationStatus(uploads = uploads)
                    OperationStatusPill(operations, visible = !action.awaitingConfirmation)
                    SideEffect {
                        selection = currentSelection
                        delete = action
                    }
                }
            }
            val targets =
                DeletionTargets(listOf(uri.toString()), listOf(135L), listOf("disposable"))
            lateinit var original: MediaSelection
            rule.runOnIdle {
                original = selection
                original.select(MediaId("disposable"))
                delete(targets)
                assertEquals(1, registry.requests.size)
            }
            rule.runOnIdle { assertTrue(delete.awaitingConfirmation) }
            rule.runOnIdle { uploads.passFinished(UploadResult(uploaded = 1, files = 1)) }
            restoration.emulateSavedInstanceStateRestore()
            // The Activity remains STARTED beneath MediaProvider's translucent confirmation.
            // A parallel result must survive more than the real six-second expiry, including restore.
            Thread.sleep(7_000)
            rule.runOnIdle { assertNotNull(uploads.result.value) }
            rule.runOnIdle {
                assertTrue(delete.awaitingConfirmation)
                assertNotSame(original, selection)
                assertTrue(selection.active)
                registry.dispatchResult(registry.requests.single(), resultCode, null)
            }
            rule.runOnIdle {
                assertFalse(delete.awaitingConfirmation)
                if (resultCode == Activity.RESULT_OK) {
                    assertEquals(listOf(targets), confirmed)
                    assertFalse(selection.active)
                    assertTrue(selection.selectedIds.isEmpty())
                } else {
                    assertTrue(confirmed.isEmpty())
                    assertTrue(selection.active)
                    assertEquals(setOf(MediaId("disposable")), selection.selectedIds)
                }
            }
            rule.waitUntil(timeoutMillis = 10_000) { uploads.result.value == null }
        } finally {
            scope.cancel()
            resolver.delete(uri, null, null)
        }
    }

    private class TrashRegistry : ActivityResultRegistry() {
        val requests = mutableListOf<Int>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            requests += requestCode
        }
    }
}

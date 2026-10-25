package com.syrok0010.nextgallery.feature.upload

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueuedUploadsTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val queue = UploadQueue(SessionStore(SignedIn), scope)
    private var starts = 0
    private val registry = PermissionRegistry()
    private var notificationsGranted = false
    private val permissionContext by lazy {
        object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.POST_NOTIFICATIONS) {
                    if (notificationsGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                } else {
                    super.checkPermission(permission, pid, uid)
                }
        }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    @Before
    fun resetPermission() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        clearPreferences()
    }

    @After
    fun cleanup() {
        clearPreferences()
        scope.cancel()
    }

    @Test
    fun denialStartsWaitingBatchOnceAndDoesNotAskAgain() {
        showQueuedUploads()
        queue.submit(BATCH)
        rule.runOnIdle {
            assertEquals(1, registry.requests.size)
            assertEquals(0, starts)
            registry.dispatchResult(registry.requests.single(), false)
        }
        rule.runOnIdle {
            assertEquals(1, starts)
            assertEquals(BATCH.files, queue.next()?.files)
        }
        queue.submit(BATCH)
        rule.runOnIdle {
            assertEquals(1, registry.requests.size)
            assertEquals(2, starts)
        }
    }

    @Test
    fun waitingBatchSurvivesRecreationAndDuplicatePermissionResult() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalContext provides permissionContext) {
                QueuedUploads(queue) { starts++ }
            }
        }
        queue.submit(BATCH)
        rule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { registry.dispatchResult(registry.requests.single(), true) }
        rule.runOnIdle { registry.dispatchResult(registry.requests.single(), true) }
        rule.runOnIdle {
            assertEquals(1, registry.requests.size)
            assertEquals(1, starts)
        }
    }

    @Test
    fun grantedPermissionStartsImmediatelyWithoutDialog() {
        notificationsGranted = true
        showQueuedUploads()
        queue.submit(BATCH)
        rule.runOnIdle {
            assertEquals(emptyList<Int>(), registry.requests)
            assertEquals(1, starts)
        }
    }

    @Test
    fun refusedStartKeepsBatchUntilAppIsVisibleAgain() {
        notificationsGranted = true
        var refuse = true
        val screen = Screen()
        rule.runOnUiThread { screen.lifecycle.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalContext provides permissionContext,
                LocalLifecycleOwner provides screen,
            ) {
                QueuedUploads(queue) {
                    starts++
                    if (refuse) throw IllegalStateException("not allowed to start service")
                }
            }
        }
        queue.submit(BATCH)
        rule.runOnIdle {
            assertEquals(1, starts)
            assertEquals(listOf(BATCH.files), queue.waiting.value.map { it.files })
            assertNull(queue.next())
        }
        queue.submit(OTHER)
        rule.runOnIdle { assertEquals("no retries while the screen stays visible", 1, starts) }

        refuse = false
        rule.runOnIdle { screen.lifecycle.currentState = Lifecycle.State.CREATED }
        rule.runOnIdle { assertEquals(1, starts) }
        rule.runOnIdle { screen.lifecycle.currentState = Lifecycle.State.RESUMED }
        rule.runOnIdle {
            assertEquals("one start for all waiting batches", 2, starts)
            assertEquals(emptyList<UploadBatch>(), queue.waiting.value)
            assertEquals(BATCH.files, queue.next()?.files)
            assertEquals(OTHER.files, queue.next()?.files)
            assertNull(queue.next())
        }
    }

    private fun showQueuedUploads() {
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalContext provides permissionContext) {
                QueuedUploads(queue) { starts++ }
            }
        }
    }

    private fun clearPreferences() {
        context
            .getSharedPreferences(
                "upload_permissions",
                Context.MODE_PRIVATE,
            ).edit()
            .clear()
            .commit()
    }

    private class PermissionRegistry : ActivityResultRegistry() {
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

    private class Screen : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private object SignedIn : CredentialsStore {
        override fun load() = AccountCredentials("http://cloud", "anna", "secret", "anna")
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    }

    private companion object {
        val BATCH = UploadBatch(
            listOf(
                UploadSelection("content://media/external/images/media/42", "фото.png", "image/png"),
                UploadSelection("content://media/external/video/media/43", "video.mp4", "video/mp4"),
            ),
        )
        val OTHER = UploadBatch(
            listOf(UploadSelection("content://media/external/images/media/44", "другое.jpg", "image/jpeg")),
        )
    }
}

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadNotificationRequestTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
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
    private lateinit var scope: CoroutineScope
    private lateinit var askForNotifications: suspend () -> Unit
    private var asked = 0

    @Before fun reset() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        clearPreferences()
    }

    @After fun cleanup() = clearPreferences()

    @Test fun asksOnceAndNeverAgain() {
        show()
        rule.runOnIdle { assertEquals(0, registry.requests.size) }
        ask()
        rule.waitUntil(5_000) { registry.requests.size == 1 }
        rule.runOnIdle { registry.dispatchResult(registry.requests.single(), false) }
        ask()
        rule.waitUntil(5_000) { asked == 2 }
        rule.runOnIdle { assertEquals(1, registry.requests.size) }
    }

    @Test fun grantedPermissionIsNotAskedFor() {
        notificationsGranted = true
        show()
        ask()
        rule.waitUntil(5_000) { asked == 1 }
        rule.runOnIdle { assertEquals(emptyList<Int>(), registry.requests) }
    }

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry: ActivityResultRegistry = registry
                },
                LocalContext provides permissionContext,
            ) {
                scope = rememberCoroutineScope()
                askForNotifications = rememberUploadNotificationRequest()
            }
        }
    }

    /** Stands for the user queueing an upload. */
    private fun ask() =
        rule.runOnIdle {
            scope.launch {
                askForNotifications()
                asked++
            }
        }

    private fun clearPreferences() {
        context.getSharedPreferences("upload_permissions", Context.MODE_PRIVATE).edit().clear().commit()
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
}

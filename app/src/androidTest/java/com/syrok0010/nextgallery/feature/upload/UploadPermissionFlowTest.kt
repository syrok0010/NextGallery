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
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadPermissionFlowTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val uploads = mutableListOf<List<UploadSelection>>()
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
        context
            .getSharedPreferences(
                "upload_permissions",
                Context.MODE_PRIVATE,
            ).edit()
            .clear()
            .commit()
    }

    @After
    fun cleanup() {
        context
            .getSharedPreferences(
                "upload_permissions",
                Context.MODE_PRIVATE,
            ).edit()
            .clear()
            .commit()
    }

    @Test
    fun denialStartsOriginalSelectionOnceAndDoesNotAskAgain() {
        showUpload()
        rule.onNodeWithText("Upload").performClick()
        rule.onNodeWithText("Upload").performClick()
        rule.runOnIdle {
            assertEquals(1, registry.requests.size)
            assertEquals(emptyList<List<UploadSelection>>(), uploads)
            registry.dispatchResult(registry.requests.single(), false)
        }
        rule.runOnIdle { assertEquals(listOf(SELECTION), uploads) }
        rule.onNodeWithText("Upload").performClick()
        rule.runOnIdle {
            assertEquals(1, registry.requests.size)
            assertEquals(listOf(SELECTION, SELECTION), uploads)
        }
    }

    @Test
    fun pendingSelectionSurvivesRecreationAndDuplicatePermissionResult() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalContext provides permissionContext) {
                val upload = rememberUploadAction { uploads += it }
                Button(onClick = { upload(SELECTION) {} }) { Text("Upload") }
            }
        }
        rule.onNodeWithText("Upload").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { registry.dispatchResult(registry.requests.single(), true) }
        rule.runOnIdle { registry.dispatchResult(registry.requests.single(), true) }
        rule.runOnIdle {
            assertEquals(1, registry.requests.size)
            assertEquals(listOf(SELECTION), uploads)
        }
    }

    @Test
    fun grantedPermissionStartsImmediatelyWithoutDialog() {
        notificationsGranted = true
        showUpload()
        rule.onNodeWithText("Upload").performClick()
        rule.runOnIdle {
            assertEquals(emptyList<Int>(), registry.requests)
            assertEquals(listOf(SELECTION), uploads)
        }
    }

    private fun showUpload() {
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalContext provides permissionContext) {
                val upload = rememberUploadAction { uploads += it }
                Button(onClick = { upload(SELECTION) {} }) { Text("Upload") }
            }
        }
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

    private companion object {
        val SELECTION = listOf(
            UploadSelection("content://media/external/images/media/42", "фото.png", "image/png"),
            UploadSelection("content://media/external/video/media/43", "video.mp4", "video/mp4"),
        )
    }
}

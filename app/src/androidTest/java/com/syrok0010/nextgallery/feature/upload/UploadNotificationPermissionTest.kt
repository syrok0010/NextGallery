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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadNotificationPermissionTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val library = MediaLibraryIndex().apply { publish(listOf(PHOTO, OTHER)) }
    private val db by lazy { UploadQueueDatabase.open(context, DB_NAME) }
    private val queue by lazy {
        UploadQueue(SessionStore(SignedIn), library, scope, UploadJournal(db),
            { SourceStamp(10, 1, 1) }, UploadFollowUps({ _, _, _, _ -> true }, { _, _, _, _ -> true }), {})
    }
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

    @Before fun reset() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        clearPreferences()
        context.deleteDatabase(DB_NAME)
    }

    @After fun cleanup() {
        clearPreferences()
        scope.cancel()
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test fun firstQueuedUploadAsksOnceAndNeverAgain() {
        show()
        rule.runOnIdle { assertEquals(0, registry.requests.size) }
        submit(PHOTO)
        rule.waitUntil(5_000) { registry.requests.size == 1 }
        rule.runOnIdle { registry.dispatchResult(registry.requests.single(), false) }
        submit(OTHER)
        rule.waitUntil(5_000) { queue.waiting.value.size == 2 }
        rule.runOnIdle { assertEquals(1, registry.requests.size) }
    }

    @Test fun grantedPermissionIsNotAskedFor() {
        notificationsGranted = true
        show()
        submit(PHOTO)
        rule.waitUntil(5_000) { queue.waiting.value.isNotEmpty() }
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
                UploadNotificationPermission(queue)
            }
        }
    }

    private fun submit(item: MediaItem) = runBlocking { queue.submit(listOf(item.mediaId)) }

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

    private object SignedIn : CredentialsStore {
        override fun load() = AccountCredentials("http://cloud", "anna", "secret", "anna")
        override fun save(credentials: AccountCredentials) = Unit
        override fun clear() = Unit
    }

    private companion object {
        const val DB_NAME = "upload-notification-permission-test.db"
        val PHOTO = phoneItem("content://media/external/images/media/42", "фото.png")
        val OTHER = phoneItem("content://media/external/images/media/44", "другое.jpg")

        fun phoneItem(contentUri: String, name: String) =
            MediaItem(
                MediaId(contentUri), 1, name, "image/jpeg", null, null, null, null, null, null, null,
                1, false, null, false, false, MediaAssetRef.LocalContent(contentUri, 1, 1),
            )
    }
}

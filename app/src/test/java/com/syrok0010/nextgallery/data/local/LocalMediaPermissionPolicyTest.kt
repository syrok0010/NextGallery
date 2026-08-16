package com.syrok0010.nextgallery.data.local

import android.Manifest
import android.os.Build
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMediaPermissionPolicyTest {
    @Test
    fun `android 14 distinguishes full partial and denied from current grants`() {
        assertEquals(
            LocalMediaPermissionMode.Full,
            LocalMediaPermissionPolicy.mode(
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                LocalMediaPermissionGrants(images = true, videos = true, selectedVisualMedia = true),
            ),
        )
        assertEquals(
            LocalMediaPermissionMode.Partial,
            LocalMediaPermissionPolicy.mode(
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                LocalMediaPermissionGrants(selectedVisualMedia = true),
            ),
        )
        assertEquals(
            LocalMediaPermissionMode.Denied,
            LocalMediaPermissionPolicy.mode(
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                LocalMediaPermissionGrants(),
            ),
        )
    }

    @Test
    fun `android 13 requires both image and video grants for full timeline`() {
        assertEquals(
            LocalMediaPermissionMode.Denied,
            LocalMediaPermissionPolicy.mode(
                Build.VERSION_CODES.TIRAMISU,
                LocalMediaPermissionGrants(images = true),
            ),
        )
        assertEquals(
            LocalMediaPermissionMode.Full,
            LocalMediaPermissionPolicy.mode(
                Build.VERSION_CODES.TIRAMISU,
                LocalMediaPermissionGrants(images = true, videos = true),
            ),
        )
    }

    @Test
    fun `requested permissions match the platform contract`() {
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            LocalMediaPermissionPolicy.requestedPermissions(Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            LocalMediaPermissionPolicy.requestedPermissions(Build.VERSION_CODES.S_V2),
        )
    }
}

package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.core.network.memories.MemoriesAlbumDto
import com.syrok0010.nextgallery.core.network.memories.MemoriesImageClustersDto
import com.syrok0010.nextgallery.core.network.memories.MemoriesImageInfoDto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerMediaDetailsTest {
    @Test
    fun normalizeLocalDirectory_stripsSlashesAndBlank() {
        assertEquals("DCIM/Camera", normalizeLocalDirectory("DCIM/Camera/"))
        assertEquals("Pictures/Screenshots", normalizeLocalDirectory("/Pictures/Screenshots/"))
        assertNull(normalizeLocalDirectory("   "))
        assertNull(normalizeLocalDirectory("/"))
        assertNull(normalizeLocalDirectory(null))
    }

    @Test
    fun normalizeRemoteDirectory_stripsFilenameAndSlashes() {
        assertEquals("Photos", normalizeRemoteDirectory("/Photos/20251206_190713.jpg"))
        assertEquals("Photos/2025", normalizeRemoteDirectory("/Photos/2025/20251206_190713.jpg"))
        assertEquals("/", normalizeRemoteDirectory("/20251206_190713.jpg"))
        assertNull(normalizeRemoteDirectory("20251206_190713.jpg"))
        assertNull(normalizeRemoteDirectory(""))
    }

    @Test
    fun localAlbumName_usesLeafFolder() {
        assertEquals("Camera", localAlbumName("DCIM/Camera"))
        assertEquals("Screenshots", localAlbumName("Screenshots"))
        assertNull(localAlbumName(null))
    }

    @Test
    fun formatCameraName_avoidsDuplicateMakePrefix() {
        assertEquals("samsung Galaxy S24 Ultra", formatCameraName("samsung", "Galaxy S24 Ultra"))
        assertEquals("Google Pixel 9", formatCameraName("Google", "Google Pixel 9"))
        assertEquals("Pixel 9", formatCameraName(null, "Pixel 9"))
        assertNull(formatCameraName(" ", ""))
    }

    @Test
    fun memoriesImageInfo_parsesExifAlbumsAndAddress() {
        val dto = MemoriesImageInfoDto(
            filename = "/Photos/20261111_193233.heic",
            basename = "20261111_193233.heic",
            size = 4_000_000L,
            address = "Нижний Новгород, Россия",
            exif = buildJsonObject {
                put("Make", "samsung")
                put("Model", "Galaxy S24 Ultra")
                put("LensModel", "Wide 23mm")
                put("FocalLength", "6.3 mm")
                put("ExposureTime", "1/100")
                put("FNumber", 1.7)
                put("ISO", "250")
                put("GPSLatitude", 56.22937)
                put("GPSLongitude", "43.41004")
            },
            clusters = MemoriesImageClustersDto(
                albums = listOf(
                    MemoriesAlbumDto(albumId = 1, name = "ДР Ефим 14 лет"),
                    MemoriesAlbumDto(albumId = 2, name = "ДР Ефим 14 лет"),
                ),
            ),
        )

        val details = dto.toViewerDetails()

        assertEquals("samsung Galaxy S24 Ultra", details.cameraModel)
        assertEquals("Wide 23mm", details.lensModel)
        assertEquals(6.3, details.focalLengthMm!!, 0.001)
        assertEquals(0.01, details.exposureTimeSeconds!!, 0.0001)
        assertEquals(1.7, details.aperture!!, 0.001)
        assertEquals(250, details.iso)
        assertEquals(4_000_000L, details.fileSizeBytes)
        assertEquals("Photos", details.remotePath)
        assertEquals(listOf("ДР Ефим 14 лет"), details.albums)
        assertEquals(ViewerGeoPoint(56.22937, 43.41004), details.location)
        assertEquals("Нижний Новгород, Россия", details.locationLabel)
    }

    @Test
    fun mergeViewerMediaDetails_keepsBothPathsAndCombinesAlbums() {
        val local = ViewerMediaDetails(
            cameraModel = "samsung Galaxy S24 Ultra",
            focalLengthMm = 6.3,
            exposureTimeSeconds = 0.01,
            aperture = 1.7,
            iso = 250,
            fileSizeBytes = 1_700_000L,
            localPath = "DCIM/Camera",
            albums = listOf("Camera"),
            location = ViewerGeoPoint(56.22937, 43.41004),
        )
        val remote = ViewerMediaDetails(
            fileSizeBytes = 1_700_000L,
            remotePath = "Photos",
            albums = listOf("ДР Ефим 14 лет", "Camera"),
            locationLabel = "Нижний Новгород, Россия",
        )

        val merged = mergeViewerMediaDetails(local, remote)

        assertEquals("samsung Galaxy S24 Ultra", merged.cameraModel)
        assertEquals(6.3, merged.focalLengthMm!!, 0.001)
        assertEquals("DCIM/Camera", merged.localPath)
        assertEquals("Photos", merged.remotePath)
        assertEquals(listOf("Camera", "ДР Ефим 14 лет"), merged.albums)
        assertEquals(ViewerGeoPoint(56.22937, 43.41004), merged.location)
        assertEquals("Нижний Новгород, Россия", merged.locationLabel)
    }
}

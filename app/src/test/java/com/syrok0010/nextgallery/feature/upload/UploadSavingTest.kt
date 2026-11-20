package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadSavingTest {
    private val job = UploadJob(
        id = "job",
        selection = UploadSelection(
            MediaAssetRef.LocalContent("content://media/external/file/1", 1, 1),
            "a.jpg",
            "image/jpeg",
        ),
        state = TransferState.Running,
        executor = "worker",
    )

    @Test fun `bytes on the wire show their percent`() {
        val entry = uploadEntries(listOf(job), Progress("job", 99L, 100L)).single()
        assertEquals(99, entry.percent)
        assertFalse(entry.saving)

        val tile = tileProgress(Progress("job", 99L, 100L))
        assertEquals(0.99f, checkNotNull(tile), 0.0001f)
    }

    @Test fun `once every byte is sent the server stores the file without progress`() {
        val entry = uploadEntries(listOf(job), Progress("job", 100L, 100L)).single()
        assertTrue(entry.saving)

        val activity =
            checkNotNull(uploadActivity(listOf(job), setOf("job"), Progress("job", 100L, 100L)))
        assertTrue(activity.saving)
        // The tile ring spins instead of standing full.
        assertNull(tileProgress(Progress("job", 100L, 100L)))
    }

    @Test fun `entries keep real bytes even before one percent`() {
        val entry = uploadEntries(listOf(job), Progress("job", 1_048_576L, 4_294_967_296L)).single()
        assertEquals(0, entry.percent)
        assertEquals(1_048_576L, entry.sentBytes)
        assertEquals(4_294_967_296L, entry.totalBytes)
        assertFalse(entry.saving)
    }

    private fun tileProgress(progress: Progress): Float? {
        val activity = uploadActivity(listOf(job), setOf("job"), progress)
        val upload = uploadOperationSnapshots(activity, result = null).single()
        return upload.media.single().progress
    }
}

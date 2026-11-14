package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadResultTest {
    @Test fun `result counts the files the user has not seen and names each problem once`() {
        val albumFailed = job("album", TransferState.Accepted, receipt(created = true))
            .copy(steps = mapOf(FollowUp.Album to StepState.Attention))
        val folderMissing = job("folder", TransferState.Attention)
            .copy(problem = UploadProblem.Directory, httpStatus = 409)
        val rows = listOf(
            job("new", TransferState.Accepted, receipt(created = true)),
            job("same", TransferState.Accepted, receipt(created = false)),
            job("cancelled", TransferState.Cancelled),
            job("taken", TransferState.Conflict),
            folderMissing,
            // The same problem for a second file is not listed twice.
            folderMissing.copy(id = "folder-again"),
            albumFailed,
            job("seen", TransferState.Accepted, receipt(created = true)).copy(seen = true),
            job("waiting", TransferState.Pending),
        )

        val result = checkNotNull(uploadResult(rows))

        assertEquals(
            UploadResult(
                files = 7,
                uploaded = 3,
                existing = 1,
                cancelled = 1,
                conflicts = 1,
                problems = listOf(folderMissing.problemText(), albumFailed.problemText()),
            ),
            result,
        )
        assertTrue(result.failed)
    }

    @Test fun `nothing unseen means no result`() {
        assertNull(
            uploadResult(
                listOf(
                    job("seen", TransferState.Accepted, receipt(created = true)).copy(seen = true),
                ),
            ),
        )
        assertNull(uploadResult(listOf(job("waiting", TransferState.Pending))))
    }

    private fun receipt(created: Boolean) =
        UploadReceipt(listOf("Photos", "a.jpg"), created, fileId = 1)

    /** Every file is named a.jpg, so problems of different files read the same. */
    private fun job(id: String, state: TransferState, receipt: UploadReceipt? = null) =
        UploadJob(
            id = id,
            selection = UploadSelection(
                MediaAssetRef.LocalContent("content://media/external/images/media/$id", 1, 1),
                "a.jpg",
                "image/jpeg",
            ),
            state = state,
            receipt = receipt,
        )
}

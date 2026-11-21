package com.syrok0010.nextgallery.feature.upload

import android.app.Notification
import android.text.format.Formatter
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import org.junit.Assert.*
import org.junit.Test

class UploadNotificationsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications get() = UploadNotifications(context)
    private val job = UploadJob(
        id = "job",
        selection = UploadSelection(
            MediaAssetRef.LocalContent(
                "content://media/external/file/1",
                1,
                1,
            ),
            "a.mp4",
            "video/mp4",
        ),
        state = TransferState.Running,
        executor = "worker",
    )

    @Test fun singleFileShowsActualBytesWithinOnePercent() {
        val first = notification(1_048_576L, 4_294_967_296L)
        val next = notification(2_097_152L, 4_294_967_296L)
        assertEquals(
            context.getString(R.string.upload_progress, 1, 1),
            next.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(
                R.string.uploads_sent,
                Formatter.formatShortFileSize(context, 2_097_152L),
                Formatter.formatShortFileSize(context, 4_294_967_296L),
            ),
            next.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertNotEquals(
            first.extras.getCharSequence(Notification.EXTRA_TEXT),
            next.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertEquals(0, next.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(next.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test fun savingShowsServerWaitInsteadOfCompletedProgress() {
        val notification = notification(100L, 100L)
        assertEquals(
            context.getString(R.string.uploads_saving),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test fun unknownSizeShowsOnlySentBytes() {
        val notification = notification(1_048_576L, -1L)
        assertEquals(
            Formatter.formatShortFileSize(context, 1_048_576L),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test fun nextFileDoesNotShowPreviousFilesBytes() {
        val activity =
            checkNotNull(
                uploadActivity(
                    listOf(job),
                    setOf("previous", "job"),
                    Progress("previous", 50L, 100L),
                ),
            )
        val notification = notifications.progress(activity)
        assertEquals(
            context.getString(R.string.upload_progress, 2, 2),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    private fun notification(sent: Long, total: Long): Notification {
        val activity =
            checkNotNull(uploadActivity(listOf(job), setOf("job"), Progress("job", sent, total)))
        return notifications.progress(activity)
    }
}

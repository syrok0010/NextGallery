package com.syrok0010.nextgallery.feature.library.local

import com.syrok0010.nextgallery.core.media.MediaId
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMediaProjectionItemTest {
    @Test fun `day follows device timezone while timestamp remains the real instant`() {
        val previous = TimeZone.getDefault()
        try {
            for ((zone, timestamp, date) in listOf(
                Triple("Europe/Moscow", "2026-11-07T22:02:00Z", "2026-11-08"),
                Triple("America/Los_Angeles", "2026-11-08T03:30:00Z", "2026-11-07"),
                Triple("UTC", "2026-11-08T03:30:00Z", "2026-11-08"),
                Triple("America/New_York", "2026-03-08T04:30:00Z", "2026-03-07"),
                Triple("Europe/Moscow", "1969-12-31T22:30:00Z", "1970-01-01"),
            )) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val epoch = Instant.parse(timestamp).epochSecond
                val item = local(epoch).toMediaItem()
                assertEquals(zone, LocalDate.parse(date), item.day)
                assertEquals(zone, epoch, item.takenAtEpochSeconds)
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private fun local(epoch: Long) = LocalMediaProjectionItem(
        mediaId = MediaId("local"), contentUri = "content://images/1", displayName = "photo.jpg",
        mimeType = "image/jpeg", width = 100, height = 100, takenAtEpochSeconds = epoch,
        modifiedAtEpochSeconds = epoch, sizeBytes = 1, isVideo = false,
        videoDurationSeconds = null, auid = null, buid = null,
    )
}

package com.syrok0010.nextgallery.feature.library.local

import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMediaTimeTest {
    @Test fun `DATE_TAKEN remains an unshifted instant and outranks EXIF fallback`() {
        withDefaults(Locale.forLanguageTag("th-TH"), "Europe/Moscow") {
            val capture = Instant.parse("2026-11-07T22:02:00Z")
            assertEquals(
                capture.epochSecond,
                localCaptureEpochSeconds(null, capture.toEpochMilli()),
            )
            assertEquals(
                capture.epochSecond,
                localCaptureEpochSeconds("2024:03:31 01:30:00", capture.toEpochMilli(), "+09:00"),
            )
        }
    }

    @Test fun `DATE_TAKEN remains unchanged across daylight saving transitions`() {
        withDefaults(Locale.ROOT, "Europe/Berlin") {
            listOf(
                "2024-03-31T00:30:00Z",
                "2024-03-31T01:30:00Z",
                "2024-03-31T23:30:00Z",
            ).forEach { value ->
                val capture = Instant.parse(value)
                assertEquals(
                    capture.epochSecond,
                    localCaptureEpochSeconds("invalid", capture.toEpochMilli()),
                )
            }
        }
    }

    @Test fun `EXIF without DATE_TAKEN uses device timezone independently of locale`() {
        withDefaults(Locale.forLanguageTag("th-TH"), "Europe/Moscow") {
            assertEquals(
                Instant.parse("2026-11-07T22:02:00Z").epochSecond,
                localCaptureEpochSeconds("2026:11:08 01:02:00", 0),
            )
        }
    }

    @Test fun `explicit EXIF offset outranks device timezone`() {
        withDefaults(Locale.ROOT, "Pacific/Honolulu") {
            assertEquals(
                Instant.parse("2026-11-07T22:02:00Z").epochSecond,
                localCaptureEpochSeconds("2026:11:08 01:02:00", null, "+03:00"),
            )
        }
    }

    @Test fun `EXIF calendar day survives a different device timezone`() {
        withDefaults(Locale.ROOT, "Pacific/Honolulu") {
            val epoch = Instant.parse("2026-11-07T22:02:00Z").epochSecond
            assertEquals(
                LocalDate.parse("2026-11-08").toEpochDay().toInt(),
                localMediaDayId(epoch, "2026:11:08 01:02:00"),
            )
            assertEquals(LocalDate.parse("2026-11-07").toEpochDay().toInt(), localMediaDayId(epoch))
            assertEquals(localMediaDayId(epoch), localMediaDayId(epoch, "2026:02:31 12:00:00"))
        }
    }

    @Test fun `missing or invalid dates leave modification and addition fallbacks available`() {
        assertNull(localCaptureEpochSeconds(null, 0))
        assertNull(localCaptureEpochSeconds(null, null))
        assertNull(localCaptureEpochSeconds("invalid", null))
        assertNull(localCaptureEpochSeconds("2026:02:31 12:00:00", null))
    }

    private fun withDefaults(locale: Locale, zone: String, block: () -> Unit) {
        val previousLocale = Locale.getDefault()
        val previousZone = TimeZone.getDefault()
        try {
            Locale.setDefault(locale)
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            block()
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousZone)
        }
    }
}

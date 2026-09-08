package com.syrok0010.nextgallery.feature.timeline.local

import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalTimelineTimeTest {
    @Test fun `EXIF is a calendar coordinate independent of device locale and zone`() {
        withDefaults(Locale.forLanguageTag("th-TH"), "Pacific/Honolulu") {
            assertEquals(Instant.parse("2024-03-31T01:30:00Z").epochSecond,
                canonicalTimelineSeconds("2024:03:31 01:30:00", 1))
        }
    }

    @Test fun `DATE_TAKEN fallback uses offset at capture across DST and midnight`() {
        withDefaults(Locale.ROOT, "Europe/Berlin") {
            listOf(
                "2024-03-31T00:30:00Z" to "2024-03-31T01:30:00Z",
                "2024-03-31T01:30:00Z" to "2024-03-31T03:30:00Z",
                "2024-03-31T23:30:00Z" to "2024-04-01T01:30:00Z",
            ).forEach { (capture, calendar) ->
                assertEquals(Instant.parse(calendar).epochSecond,
                    canonicalTimelineSeconds("invalid", Instant.parse(capture).toEpochMilli()))
            }
            assertNull(canonicalTimelineSeconds(null, 0))
            assertNull(canonicalTimelineSeconds(null, null))
        }
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

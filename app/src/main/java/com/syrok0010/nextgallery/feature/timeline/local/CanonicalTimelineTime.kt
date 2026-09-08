package com.syrok0010.nextgallery.feature.timeline.local

import java.text.SimpleDateFormat
import java.util.TimeZone

/**
 * Calendar coordinate compatible with Memories, not a UTC capture instant.
 * Raw DATE_TAKEN remains unchanged for AUID; never apply this normalization twice.
 */
internal fun canonicalTimelineSeconds(
    exifDateTime: String?,
    dateTakenMillis: Long?,
): Long? {
    if (exifDateTime != null) {
        runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.parse(exifDateTime)?.time?.div(1_000)
        }.getOrNull()?.let { return it }
    }
    return dateTakenMillis?.takeIf { it > 0 }?.let { timestamp ->
        (timestamp + TimeZone.getDefault().getOffset(timestamp)) / 1_000
    }
}

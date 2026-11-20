package com.syrok0010.nextgallery.feature.library.local

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** DATE_TAKEN is already a real instant; never add a timezone offset to it. */
internal fun localCaptureEpochSeconds(
    exifDateTime: String?,
    dateTakenMillis: Long?,
    exifOffset: String? = null,
): Long? {
    dateTakenMillis?.takeIf { it > 0 }?.let { return it / 1_000 }
    if (exifDateTime == null) return null
    return runCatching {
        val date = LocalDateTime.parse(exifDateTime, EXIF_DATE_TIME)
        val zone = exifOffset?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
        date.atZone(zone).toEpochSecond()
    }.getOrNull()
}

/** Memories groups EXIF photos by their recorded calendar date; other files use device time. */
internal fun localMediaDayId(epochSeconds: Long, exifDateTime: String? = null): Int {
    val exifDate = exifDateTime?.let {
        runCatching { LocalDateTime.parse(it, EXIF_DATE_TIME).toLocalDate() }.getOrNull()
    }
    val date = exifDate ?: Instant
        .ofEpochSecond(
            epochSeconds,
        ).atZone(ZoneId.systemDefault())
        .toLocalDate()
    return date.toEpochDay().toInt()
}

private val EXIF_DATE_TIME = DateTimeFormatter
    .ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

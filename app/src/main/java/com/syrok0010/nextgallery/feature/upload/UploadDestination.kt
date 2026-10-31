package com.syrok0010.nextgallery.feature.upload

/** Memories config contains a semicolon-separated list, not a single DAV directory. */
internal fun timelinePaths(value: String?): List<String> =
    value?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

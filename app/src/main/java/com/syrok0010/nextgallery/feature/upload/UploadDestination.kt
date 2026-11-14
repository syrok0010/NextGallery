package com.syrok0010.nextgallery.feature.upload

/**
 * The folder uploads go to: the first entry of Memories' `timeline_path`, which is a
 * semicolon-separated list rather than a single DAV directory (ADR 0014). Null without one.
 */
internal fun uploadDestination(timelinePath: String?): String? =
    timelinePath?.split(';')?.map(String::trim)?.firstOrNull(String::isNotEmpty)

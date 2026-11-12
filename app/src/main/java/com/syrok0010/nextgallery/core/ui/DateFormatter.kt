package com.syrok0010.nextgallery.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.syrok0010.nextgallery.R
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A formatter for the [pattern] string. Month and weekday names follow the language of the
 * strings, not of the system: an unsupported system language falls back to English strings.
 */
@Composable
fun rememberDateFormatter(@StringRes pattern: Int): DateTimeFormatter {
    val text = stringResource(pattern)
    val language = stringResource(R.string.language_tag)
    return remember(text, language) {
        DateTimeFormatter.ofPattern(text, Locale.forLanguageTag(language))
    }
}

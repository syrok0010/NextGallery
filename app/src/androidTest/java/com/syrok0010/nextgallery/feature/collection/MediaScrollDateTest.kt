package com.syrok0010.nextgallery.feature.collection

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaScrollDateTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lastDecembersYearIsVisibleInTheScrollTooltip() = checkDate("ru", 1f, 12, true)

    @Test fun lastSeptembersYearFitsWithLargerRussianFont() = checkDate("ru", 1.5f, 9, true)

    @Test fun lastSeptembersYearFitsWithLargerEnglishFont() = checkDate("en", 1.5f, 9, true)

    @Test fun currentYearStaysOmitted() = checkDate("ru", 1.5f, 12, false)

    private fun checkDate(language: String, fontScale: Float, month: Int, previousYear: Boolean) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
            this.fontScale = fontScale
        }
        val context = base.createConfigurationContext(configuration)
        val date = LocalDate.of(LocalDate.now().year - if (previousYear) 1 else 0, month, 21)
        val pattern = if (language == "ru") {
            if (previousYear) "d MMMM yyyy" else "d MMMM"
        } else {
            if (previousYear) "MMMM d, yyyy" else "MMMM d"
        }
        val label = date.format(
            DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(language)),
        )
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(base.resources.displayMetrics.density, fontScale),
            ) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(600.dp)) {
                        MediaScrollIndicator(
                            dayId = { date.toEpochDay().toInt() },
                            fraction = { 0.5f },
                            isTooltipVisible = { true },
                            onDragStateChange = {},
                            onFractionChange = {},
                        )
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule
            .onNodeWithText(label)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(label.length, layout.getLineEnd(0))
        assertTrue(layout.getLineLeft(0) >= 0f)
        assertTrue(layout.getLineRight(0) <= layout.size.width)
    }
}

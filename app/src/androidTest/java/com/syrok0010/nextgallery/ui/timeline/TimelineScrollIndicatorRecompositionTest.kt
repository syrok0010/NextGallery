package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineScrollIndicatorRecompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fractionUpdatesDoNotRecomposeIndicator() {
        val fraction = mutableFloatStateOf(0f)
        val compositionCount = AtomicInteger()

        composeRule.setContent {
            SideEffect {
                compositionCount.incrementAndGet()
            }
            TimelineScrollIndicator(
                dayId = 0,
                fraction = { fraction.floatValue },
                isTooltipVisible = false,
                onDragStateChange = {},
                onFractionChange = {},
            )
        }
        composeRule.waitForIdle()
        val initialCompositionCount = compositionCount.get()

        repeat(20) { index ->
            composeRule.runOnIdle {
                fraction.floatValue = (index + 1) / 20f
            }
        }

        assertEquals(initialCompositionCount, compositionCount.get())
    }
}

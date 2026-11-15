package com.syrok0010.nextgallery.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoriesCompatibilityTest {
    @Test fun `11 releases and later majors pass the minimum version check`() {
        listOf("11.0.0", "11.1", "11.1.4", "11.1.4-beta.1+build.2", "12.0.0").forEach {
            assertEquals(it, MemoriesCompatibility.Supported, memoriesCompatibility(it))
        }
    }

    @Test fun `releases before 11 are too old`() {
        listOf("8.0.0", "9.0.1", "10.0.0", "10.1.0").forEach {
            assertEquals(MemoriesCompatibility.TooOld(it), memoriesCompatibility(it))
        }
    }

    @Test fun `garbage and missing versions cannot establish compatibility`() {
        listOf(
            null,
            "",
            "garbage",
            "10.garbage",
            "10.1.0garbage",
            "-10.1.0",
            "999999999999.0.0",
        ).forEach {
            assertEquals(it, MemoriesCompatibility.UnknownVersion, memoriesCompatibility(it))
        }
    }
}

package com.syrok0010.nextgallery.feature.auth

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoriesCompatibilityTest {
    @Test fun `10 releases and later majors pass the minimum version check`() {
        listOf("10.0.0", "10.1", "10.1.0", "10.1.0-beta.1+build.2", "11.0.0").forEach {
            assertEquals(it, MemoriesCompatibility.Supported, memoriesCompatibility(it))
        }
    }

    @Test fun `8 and 9 releases are too old`() {
        listOf("8.0.0", "8.1.0", "9.0.1").forEach {
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

    @Test fun `describe missing null or non-string version is unknown`() {
        listOf("{}", """{"version":null}""", """{"version":10}""", """{"version":{}}""").forEach {
            assertEquals(
                MemoriesCompatibility.UnknownVersion,
                Json.decodeFromString<MemoriesDescribeDto>(it).compatibility(),
            )
        }
    }
}

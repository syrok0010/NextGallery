package com.syrok0010.nextgallery.core.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class BestEffortTest {
    @Test fun `cache miss and recoverable storage failure keep different results`() = runTest {
        val miss = bestEffort<String?> { null }
        assertNull(miss.getOrThrow())
        val failure = IOException("disk unavailable")
        assertSame(failure, bestEffort<String> { throw failure }.exceptionOrNull())
    }

    @Test fun `cancellation is rethrown rather than turned into cache miss`() {
        val cancellation = CancellationException("cancelled")
        val caught = assertThrows(CancellationException::class.java) {
            runTest { bestEffort<String> { throw cancellation } }
        }
        assertSame(cancellation, caught)
    }
}

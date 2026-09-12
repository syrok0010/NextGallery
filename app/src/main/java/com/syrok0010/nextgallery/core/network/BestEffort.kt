package com.syrok0010.nextgallery.core.network

import kotlinx.coroutines.CancellationException

/** Best-effort I/O may fail, but must never turn cancellation into a successful fallback. */
internal suspend fun <T> bestEffort(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    java.util.logging.Logger.getLogger("NextGallery.Cache").warning("Best-effort operation failed: ${failure.javaClass.simpleName}")
    Result.failure(failure)
}

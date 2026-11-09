package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.session.AccountCredentials

/** Work after the [UploadJob.receipt], run in this order. The journal tracks only each step's state. */
internal enum class FollowUp { Link, Album }

internal enum class StepState { Pending, Done, Attention, Suppressed }

/**
 * One step after the file is on the server. The step stays pending until its result is written,
 * so after a stop it runs again and must be safe to repeat. Returns false when the user has to
 * act; a temporary error (no network, 5xx) leaves it pending for the next attempt.
 */
internal fun interface UploadFollowUp {
    /**
     * [recovered] is false only for the attempt that accepted [file] just now. A later one may
     * follow an attempt whose outcome is unknown, so a step that is not idempotent checks the
     * server first.
     */
    suspend fun run(
        account: AccountCredentials,
        job: UploadJob,
        file: UploadedFile,
        recovered: Boolean,
    ): Boolean
}

/**
 * What every [FollowUp] does. A new step is a [FollowUp] value, its action here and a line for
 * its problem; the journal, the queue and the worker stay as they are.
 */
internal class UploadFollowUps(
    private val link: UploadFollowUp,
    private val album: UploadFollowUp,
) {
    operator fun get(step: FollowUp): UploadFollowUp =
        when (step) {
            FollowUp.Link -> link
            FollowUp.Album -> album
        }
}

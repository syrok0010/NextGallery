package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.network.dav.DavHttpException
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.core.ui.uiText
import java.io.IOException

/** Why a transfer needs the user; shown on the uploads screen and in the result. */
internal enum class UploadProblem { Source, Timeline, Directory, Http, Memories, Unexpected }

/** A failure the user can act on; [httpStatus] is the server answer when there was one. */
internal class UploadException(val problem: UploadProblem, val httpStatus: Int? = null) :
    Exception()

/**
 * No connection, a timeout, a server that is busy or down, or a lock (423) still held by an
 * interrupted request; another attempt may succeed. A full server (507) is not: it needs the user.
 */
internal val Exception.temporary: Boolean
    get() = when (this) {
        is UploadException -> httpStatus?.let(::temporaryStatus) == true
        is DavHttpException -> temporaryStatus(code)
        is IOException -> true
        else -> false
    }

private fun temporaryStatus(code: Int) =
    code == 408 || code == 423 || code == 429 || (code >= 500 && code != INSUFFICIENT_STORAGE)

private const val INSUFFICIENT_STORAGE = 507

/** A failed step is named first: the transfer itself succeeded. */
internal fun UploadJob.problemText(): UiText {
    val name = selection.displayName
    if (state != TransferState.Attention) {
        when (steps.entries.firstOrNull { it.value == StepState.Attention }?.key) {
            FollowUp.Link -> return uiText(R.string.upload_error_link)
            FollowUp.Album -> return uiText(R.string.upload_error_album)
            null -> Unit
        }
    }
    return when (problem ?: UploadProblem.Unexpected) {
        UploadProblem.Source -> uiText(R.string.upload_error_source, name)
        UploadProblem.Timeline -> uiText(R.string.upload_error_timeline)
        UploadProblem.Directory -> uiText(R.string.upload_error_directory, httpStatus ?: 0, name)
        UploadProblem.Http -> uiText(R.string.upload_error_http, httpStatus ?: 0, name)
        UploadProblem.Memories -> uiText(R.string.upload_error_memories, httpStatus ?: 0)
        UploadProblem.Unexpected -> uiText(R.string.upload_error_unexpected)
    }
}

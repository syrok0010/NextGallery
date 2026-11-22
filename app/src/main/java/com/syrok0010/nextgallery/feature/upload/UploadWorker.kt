package com.syrok0010.nextgallery.feature.upload

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Runs the jobs of [UploadQueue] as one unique work. It knows nothing about why a file is uploaded:
 * the journal decides which steps remain after the transfer. A job that failed temporarily makes
 * the whole work retry with backoff, for as long as the user does not cancel it.
 */
internal class UploadWorker(
    context: Context,
    params: WorkerParameters,
    internal val queue: UploadQueue?,
    private val transport: NextcloudTransport?,
    private val memories: MemoriesApiClient?,
) : CoroutineWorker(context, params) {
    private val notifications = UploadNotifications(context)

    override suspend fun doWork(): Result =
        try {
            if (run()) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A failed work fails the runs appended after it too, and their jobs would wait for
            // the next start of the app. The journal still holds them, so try again later.
            Log.w("UploadWorker", "Upload pass failed, retrying", error)
            Result.retry()
        }

    /** Returns true when a job waits for another attempt. */
    private suspend fun run(): Boolean {
        val queue = queue ?: return false
        val transport = transport ?: return false
        val memories = memories ?: return false
        notifications.createChannel()
        val foreground = try {
            setForeground(getForegroundInfo())
            true
        } catch (_: IllegalStateException) {
            // Android refuses a foreground service to an app in the background; the work still
            // runs, only without the notification and with the system's time limit.
            false
        }
        val transfer =
            UploadTransfer(applicationContext.contentResolver, transport, memories, queue)
        val retry = coroutineScope {
            val progress = launch { if (foreground) publishProgress(queue) }
            queue.drain(transfer::upload).also { progress.cancel() }
        }
        queue.unseenResult()?.let(notifications::showResult)
        return retry
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            UploadNotifications.PROGRESS_ID,
            notifications.progress(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    /** Android drops bursts of notification updates; the delay leaves room for the result. */
    private suspend fun publishProgress(queue: UploadQueue) {
        queue.activity.filterNotNull().collect { activity ->
            notifications.showProgress(activity)
            delay(500L)
        }
    }

    companion object {
        private const val WORK_NAME = "uploads"

        /**
         * Makes sure a worker takes the waiting jobs. A run is appended to the existing one rather
         * than replacing it: a running transfer would restart and a worker in backoff would retry
         * at once. The appended run drains whatever the current one has missed.
         *
         * [now] replaces the work instead, for exactly that early retry: the user asks for it
         * while a worker waits out its backoff and none is transferring.
         */
        suspend fun schedule(context: Context, now: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request,
                ).await()
        }
    }
}

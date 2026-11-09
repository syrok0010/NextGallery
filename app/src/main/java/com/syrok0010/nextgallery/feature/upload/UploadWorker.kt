package com.syrok0010.nextgallery.feature.upload

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.syrok0010.nextgallery.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

/**
 * Runs the jobs of [UploadQueue] as one unique work. It knows nothing about why a file is uploaded:
 * the journal decides which steps remain after the transfer. A job that failed temporarily makes
 * the whole work retry with backoff, for as long as the user does not cancel it.
 */
internal class UploadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val queue: UploadQueue by inject()
    private val notifications = UploadNotifications(context)

    override suspend fun doWork(): Result {
        notifications.createChannel()
        val foreground = try {
            setForeground(getForegroundInfo())
            true
        } catch (_: IllegalStateException) {
            // Android refuses a foreground service to an app in the background; the work still
            // runs, only without the notification and with the system's time limit.
            false
        }
        val transfer = UploadTransfer(applicationContext.contentResolver, get(), get(), queue)
        val retry = coroutineScope {
            val progress = launch { if (foreground) publishProgress() }
            queue.drain(transfer::upload).also { progress.cancel() }
        }
        queue.unseenResult()?.let(notifications::showResult)
        return if (retry) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            UploadNotifications.PROGRESS_ID,
            notifications.progress(
                applicationContext.getString(R.string.upload_preparing),
                percent = null,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    /** Android drops bursts of notification updates; the delay leaves room for the result. */
    private suspend fun publishProgress() {
        queue.activity.filterNotNull().collect { activity ->
            val message = applicationContext.getString(
                R.string.upload_progress,
                (activity.processed + 1).coerceAtMost(activity.total),
                activity.total,
            )
            notifications.showProgress(message, activity.percent)
            delay(500L)
        }
    }

    companion object {
        private const val WORK_NAME = "uploads"

        /**
         * Makes sure a worker takes the waiting jobs. A running worker is not replaced: its
         * transfer would restart. A follow-up run is appended instead, in case it has just found
         * the journal empty. A worker waiting for backoff or network is replaced to try now.
         */
        suspend fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            val running = work.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().any {
                it.state == WorkInfo.State.RUNNING
            }
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            work.enqueueUniqueWork(
                WORK_NAME,
                if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

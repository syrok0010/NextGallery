package com.syrok0010.nextgallery.feature.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.content.getSystemService
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
import com.syrok0010.nextgallery.AppLinks
import com.syrok0010.nextgallery.MainActivity
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.DavHttpException
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.ui.asString
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.HttpException

/**
 * Runs the jobs of [UploadQueue] as one unique work. It knows nothing about why a file is uploaded:
 * the journal decides which steps remain after the transfer. A job that failed temporarily makes
 * the whole work retry with backoff, for as long as the user does not cancel it.
 */
internal class UploadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val transport: NextcloudTransport by inject()
    private val memories: MemoriesApiClient by inject()
    private val queue: UploadQueue by inject()
    private val notificationManager: NotificationManager by lazy { checkNotNull(applicationContext.getSystemService()) }

    override suspend fun doWork(): Result {
        createNotificationChannel()
        val foreground = try {
            setForeground(getForegroundInfo())
            true
        } catch (_: IllegalStateException) {
            // Android refuses a foreground service to an app in the background; the work still
            // runs, only without the notification and with the system's time limit.
            false
        }
        var destination: String? = null
        val retry = coroutineScope {
            val progress = launch { if (foreground) publishProgress() }
            queue.drain { account, job, onProgress ->
                upload(account, job, { destination ?: timelineDirectory().also { destination = it } }, onProgress)
            }.also { progress.cancel() }
        }
        queue.unseenResult()?.let { notificationManager.notify(RESULT_NOTIFICATION_ID, resultNotification(it)) }
        return if (retry) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            FOREGROUND_NOTIFICATION_ID,
            notification(applicationContext.getString(R.string.upload_preparing), progress = null),
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
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification(message, activity.percent))
            delay(500L)
        }
    }

    private suspend fun timelineDirectory(): String {
        val config = try {
            memories.config()
        } catch (error: HttpException) {
            throw UploadException(UploadProblem.Memories, error.code())
        }
        return timelinePaths(config.timelinePath).firstOrNull() ?: throw UploadException(UploadProblem.Timeline)
    }

    /** The original name in the timeline folder; after a restart its neighbours are checked again. */
    private suspend fun upload(
        account: AccountCredentials,
        job: UploadJob,
        directory: suspend () -> String,
        onProgress: (Int?) -> Unit,
    ): UploadedFile? {
        val pinned = job.destination?.let { DavFilePath(account.userId, it) }
            ?: queue.pin(job, DavFilePath.of(account.userId, directory(), job.selection.displayName))
        return try {
            put(account, pinned, job.selection, onProgress)
        } catch (_: SourceUnavailable) {
            throw UploadException(UploadProblem.Source)
        }
    }

    /** Returns null when no free name is left for a file that differs from the taken ones. */
    private suspend fun put(
        account: AccountCredentials,
        path: DavFilePath,
        selection: UploadSelection,
        onProgress: (Int?) -> Unit,
    ): UploadedFile? {
        val resolver = applicationContext.contentResolver
        val uri = Uri.parse(selection.contentUri)
        val length = readSource {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        } ?: -1L
        onProgress(if (length >= 0L) 0 else null)
        val openLocal = {
            val stream = readSource { resolver.openInputStream(uri) }
            stream ?: throw SourceUnavailable()
        }
        val body = UploadRequestBody(
            length = length,
            mediaType = selection.mimeType?.toMediaTypeOrNull(),
            openStream = openLocal,
            onProgress = onProgress,
        )
        // Signed by the job's account, not whichever session is current by now.
        val dav = FilesDavClient(transport) { account }
        return try {
            placeUpload(dav, path, selection, body, openLocal)
        } catch (error: DavHttpException) {
            val problem = if (error.code == 404 || error.code == 409) UploadProblem.Directory else UploadProblem.Http
            throw UploadException(problem, error.code)
        }
    }

    private fun resultNotification(result: UploadResult): Notification {
        val lines = result.details().map { it.asString(applicationContext) }
        return Notification
            .Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentIntent(uploadsIntent())
            .setContentTitle(result.title().asString(applicationContext))
            .apply {
                if (lines.isNotEmpty()) {
                    setContentText(lines.joinToString(" · "))
                    setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
                }
            }.setOnlyAlertOnce(true)
            .build()
    }

    private fun notification(message: String, progress: Int?): Notification =
        Notification
            .Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentIntent(uploadsIntent())
            .setContentTitle(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress ?: 0, progress == null)
            .build()

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.upload_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun uploadsIntent(): PendingIntent =
        PendingIntent.getActivity(
            applicationContext,
            128,
            Intent(Intent.ACTION_VIEW, AppLinks.Uploads, applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The local file can no longer be read. An [IOException], so OkHttp passes it out of the
     * request body unchanged instead of rethrowing it on its dispatcher thread.
     */
    private class SourceUnavailable(cause: Throwable? = null) : IOException(cause)

    /** Turns a file that vanished or lost its permission into [SourceUnavailable]. */
    private inline fun <T> readSource(open: () -> T): T =
        try {
            open()
        } catch (error: FileNotFoundException) {
            throw SourceUnavailable(error)
        } catch (error: SecurityException) {
            throw SourceUnavailable(error)
        }

    companion object {
        private const val CHANNEL_ID = "cloud-upload"
        private const val FOREGROUND_NOTIFICATION_ID = 42
        private const val RESULT_NOTIFICATION_ID = 43
        private const val WORK_NAME = "uploads"

        /**
         * Makes sure a worker takes the waiting jobs. A running worker is not replaced: its
         * transfer would restart. A follow-up run is appended instead, in case it has just found
         * the journal empty. A worker waiting for backoff or network is replaced to try now.
         */
        suspend fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            val running = work.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().any { it.state == WorkInfo.State.RUNNING }
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            work.enqueueUniqueWork(
                WORK_NAME,
                if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

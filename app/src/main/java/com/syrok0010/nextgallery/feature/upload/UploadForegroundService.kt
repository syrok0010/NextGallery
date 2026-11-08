package com.syrok0010.nextgallery.feature.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.koin.android.ext.android.inject
import retrofit2.HttpException

/**
 * Runs the jobs of [UploadQueue] in the foreground. It knows nothing about why a file is uploaded:
 * the journal decides which steps remain after the transfer.
 */
class UploadForegroundService : Service() {
    private val transport: NextcloudTransport by inject()
    private val memories: MemoriesApiClient by inject()
    private val queue: UploadQueue by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager: NotificationManager by lazy { checkNotNull(getSystemService()) }

    // Main thread only: starts arrive there, and the worker resumes there.
    private var worker: Job? = null
    private var lastStartId = 0
    private var startedAgain = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (worker?.isActive == true) {
            // A job queued after the worker found none would wait for the next start otherwise.
            startedAgain = true
            return START_NOT_STICKY
        }
        try {
            // startForegroundService requires startForeground even when there is nothing left to do.
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification(getString(R.string.upload_preparing), uploading = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (_: RuntimeException) {
            // The jobs stay queued for the next time the app is visible.
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        worker = serviceScope.launch { work() }
        return START_NOT_STICKY
    }

    private suspend fun work() {
        var destination: String? = null
        while (true) {
            startedAgain = false
            coroutineScope {
                val progress = launch { publishProgress() }
                withContext(Dispatchers.IO) {
                    queue.drain { account, job, onProgress ->
                        upload(account, job, { destination ?: timelineDirectory().also { destination = it } }, onProgress)
                    }
                }
                progress.cancel()
            }
            val result = queue.unseenResult()
            // No suspension from this check to the stop, so no start can slip in between.
            if (startedAgain) continue
            if (result != null) notificationManager.notify(RESULT_NOTIFICATION_ID, resultNotification(result))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(lastStartId)
            return
        }
    }

    /** Android drops bursts of notification updates; the delay leaves room for the result. */
    private suspend fun publishProgress() {
        queue.activity.filterNotNull().collect { activity ->
            val message = getString(R.string.upload_progress, (activity.processed + 1).coerceAtMost(activity.total), activity.total)
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification(message, uploading = true, progress = activity.percent))
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

    private fun resultNotification(result: UploadResult): Notification {
        val lines = result.details().map { it.asString(this) }
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentIntent(uploadsIntent())
            .setContentTitle(result.title().asString(this))
            .apply {
                if (lines.isNotEmpty()) {
                    setContentText(lines.joinToString(" · "))
                    setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
                }
            }.setOnlyAlertOnce(true)
            .build()
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
        val uri = Uri.parse(selection.contentUri)
        val length = readSource {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        } ?: -1L
        onProgress(if (length >= 0L) 0 else null)
        val openLocal = {
            val stream = readSource { contentResolver.openInputStream(uri) }
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

    private fun notification(
        message: String,
        uploading: Boolean = false,
        progress: Int? = null,
    ): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentIntent(uploadsIntent())
            .setContentTitle(message)
            .setOngoing(uploading)
            .setOnlyAlertOnce(true)
            .apply { if (uploading) setProgress(100, progress ?: 0, progress == null) }
            .build()

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.upload_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun uploadsIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            128,
            Intent(Intent.ACTION_VIEW, AppLinks.Uploads, this, MainActivity::class.java)
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

        /** Throws if the system does not let the app start a foreground service right now. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UploadForegroundService::class.java),
            )
        }
    }
}

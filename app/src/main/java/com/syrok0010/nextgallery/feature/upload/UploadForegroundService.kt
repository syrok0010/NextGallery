package com.syrok0010.nextgallery.feature.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.core.ui.asString
import com.syrok0010.nextgallery.core.ui.pluralText
import com.syrok0010.nextgallery.feature.timeline.remote.memoriesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.koin.android.ext.android.inject

/**
 * Uploads batches from [UploadQueue] and runs their follow-ups. It knows nothing about why a file
 * is uploaded: everything after the upload belongs to [UploadFollowUp].
 */
class UploadForegroundService : Service() {
    private val credentialsStore: CredentialsStore by inject()
    private val transport: NextcloudTransport by inject()
    private val queue: UploadQueue by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager: NotificationManager by lazy {
        checkNotNull(
            getSystemService(),
        )
    }

    // Main thread only: starts arrive there, and the worker runs there.
    private var worker: Job? = null
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // A running worker picks up the new batch and stops the service with the latest start id.
        // Not a null check: with an empty queue the worker may finish before launch returns.
        if (worker?.isActive != true) {
            // startForegroundService requires startForeground even when the queue is already empty.
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification(getString(R.string.upload_preparing), uploading = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            worker = serviceScope.launch { drainQueue() }
        }
        return START_NOT_STICKY
    }

    /**
     * Batches run one after another as one pass under one foreground notification. Progress and
     * the result count files of the whole pass, so a batch released while another is running
     * extends the same counter instead of starting a new one. The service leaves the foreground
     * only when no batch is waiting.
     */
    private suspend fun drainQueue() {
        val pass = UploadPass()
        while (true) {
            val batch = queue.next() ?: break
            runBatch(batch, pass)
        }
        // No suspension from the empty check to here, so no start can slip in between.
        if (pass.files > 0) {
            notificationManager.notify(
                RESULT_NOTIFICATION_ID,
                resultNotification(pass),
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    private suspend fun runBatch(batch: UploadBatch, pass: UploadPass) {
        pass.files += batch.files.size
        val before = pass.processed
        // Android drops bursts of notification updates, including the terminal result.
        // Keep only the latest progress and leave room for foreground/final updates.
        val updates = Channel<Notification>(Channel.CONFLATED)
        val progress = UploadProgress()
        val result = coroutineScope {
            val publisher = launch {
                for (update in updates) {
                    notificationManager.notify(FOREGROUND_NOTIFICATION_ID, update)
                    delay(500L)
                }
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    upload(batch, progress) { index, percent ->
                        // Read per update: batches released meanwhile join the total at once.
                        val total = pass.files + queue.releasedFiles()
                        val message = getString(R.string.upload_progress, before + index + 1, total)
                        updates.trySend(notification(message, uploading = true, progress = percent))
                    }
                }
            }
            publisher.cancelAndJoin()
            updates.close()
            result
        }
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        result.exceptionOrNull()?.let { pass.failures += failureReason(it) }
        pass.processed += batch.files.size
        pass.uploaded += progress.completed
        pass.nameTaken += progress.nameTaken.size
        val missed = progress.nameTaken + batch.files.drop(progress.processed)
        batch.followUps.forEach { followUp ->
            pass.details += guarded(emptyList()) { followUp.finished(missed) }
        }
        // Leaves room for the result after the last progress update.
        delay(500L)
    }

    private fun resultNotification(pass: UploadPass): Notification {
        val lines = buildList {
            if (pass.failures.isNotEmpty()) {
                add(getString(R.string.upload_count, pass.uploaded, pass.files))
            }
            if (pass.nameTaken > 0) {
                add(
                    pluralText(
                        R.plurals.upload_name_taken,
                        pass.nameTaken,
                        pass.nameTaken,
                    ).asString(this@UploadForegroundService),
                )
            }
            pass.details.mapTo(this) { it.asString(this@UploadForegroundService) }
        }
        val title = if (pass.failures.isEmpty()) {
            getString(R.string.upload_done, pass.uploaded, pass.files)
        } else {
            getString(R.string.upload_stopped, pass.failures.distinct().joinToString(", "))
        }
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle(title)
            .apply {
                if (lines.isNotEmpty()) {
                    setContentText(lines.joinToString(" · "))
                    setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
                }
            }.setOnlyAlertOnce(true)
            .build()
    }

    private fun failureReason(error: Throwable): String =
        (error as? UploadException)?.let { getString(it.reason, *it.args) }
            ?: error.localizedMessage
            ?: getString(R.string.upload_error_network)

    private suspend fun upload(
        batch: UploadBatch,
        progress: UploadProgress,
        onProgress: (index: Int, percent: Int?) -> Unit,
    ) {
        val credentials = credentialsStore.load()
            ?: throw UploadException(R.string.upload_error_session)
        val config = transport.memoriesApi(credentials).config()
        val destination = config.timelinePath?.takeIf { it.isNotBlank() }
            ?: throw UploadException(R.string.upload_error_timeline)
        batch.files.forEachIndexed { index, selection ->
            val uploaded = put(
                credentials = credentials,
                url = uploadUrl(
                    credentials.serverUrl,
                    credentials.userId,
                    destination,
                    selection.displayName,
                ),
                selection = selection,
                onProgress = { percent -> onProgress(index, percent) },
            )
            progress.processed++
            if (uploaded == null) {
                progress.nameTaken += selection
            } else {
                progress.completed++
                batch.followUps.forEach { followUp ->
                    guarded(Unit) { followUp.uploaded(credentials, uploaded) }
                }
            }
        }
    }

    /**
     * Returns null when the name is taken by a different file: an existing file counts as this
     * upload only if sizes match, since the server keeps no hash to compare.
     */
    private fun put(
        credentials: AccountCredentials,
        url: HttpUrl,
        selection: UploadSelection,
        onProgress: (Int?) -> Unit,
    ): UploadedFile? {
        val uri = Uri.parse(selection.contentUri)
        val length = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        onProgress(if (length >= 0L) 0 else null)
        val body = UploadRequestBody(
            length = length,
            mediaType = selection.mimeType?.toMediaTypeOrNull(),
            openStream = {
                contentResolver.openInputStream(uri)
                    ?: throw UploadException(R.string.upload_error_open, selection.displayName)
            },
            onProgress = onProgress,
        )
        val request = transport
            .authenticatedRequestBuilder(credentials, url.toString(), accept = "*")
            .method("PUT", body)
            .header("If-None-Match", "*")
            .build()
        val existed = transport.baseClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 412) {
                throw UploadException(
                    R.string.upload_error_http,
                    response.code,
                    selection.displayName,
                )
            }
            response.code == 412
        }
        if (existed && !sameRemoteSize(credentials, url, length)) return null
        return UploadedFile(selection, url, created = !existed)
    }

    private fun sameRemoteSize(
        credentials: AccountCredentials,
        url: HttpUrl,
        length: Long,
    ): Boolean {
        if (length < 0L) return false
        val request = transport
            .authenticatedRequestBuilder(credentials, url.toString(), accept = "*/*")
            .head()
            .build()
        return runCatching {
            transport.baseClient.newCall(request).execute().use { response ->
                response.isSuccessful &&
                    response.header("Content-Length")?.toLongOrNull() == length
            }
        }.getOrDefault(false)
    }

    private fun notification(
        message: String,
        uploading: Boolean = false,
        progress: Int? = null,
    ): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
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

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Results of all batches in one pass of the queue; changed on the main thread only. */
    private class UploadPass {
        var files = 0
        var processed = 0
        var uploaded = 0
        var nameTaken = 0
        val failures = mutableListOf<String>()
        val details = mutableListOf<UiText>()
    }

    private class UploadException(@StringRes val reason: Int, vararg val args: Any) : Exception()

    /** Survives a failed upload so follow-ups still learn which files were missed. */
    private class UploadProgress {
        var processed = 0
        var completed = 0
        val nameTaken = mutableListOf<UploadSelection>()
    }

    companion object {
        private const val CHANNEL_ID = "cloud-upload"
        private const val FOREGROUND_NOTIFICATION_ID = 42
        private const val RESULT_NOTIFICATION_ID = 43

        /**
         * Takes all batches released by [UploadQueue], including ones released while it runs. Throws
         * if the system does not let the app start a foreground service right now.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UploadForegroundService::class.java),
            )
        }
    }
}

/** A failing follow-up must not stop the upload or the other follow-ups. */
private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback
    }

/** DAV URL of an upload in the user's files; [user] is the Nextcloud uid, not the login name. */
internal fun uploadUrl(serverUrl: String, user: String, directory: String, name: String): HttpUrl =
    NextcloudTransport
        .normalizeServerOrigin(serverUrl)
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("remote.php")
        .addPathSegment("dav")
        .addPathSegment("files")
        .addPathSegment(user)
        .apply { directory.split('/').filter(String::isNotEmpty).forEach(::addPathSegment) }
        .addPathSegment(name)
        .build()

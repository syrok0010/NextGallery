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
import com.syrok0010.nextgallery.feature.albums.AlbumAddress
import com.syrok0010.nextgallery.feature.albums.AlbumCatalogRepository
import com.syrok0010.nextgallery.feature.albums.AlbumCopyOutcome
import com.syrok0010.nextgallery.feature.albums.MemoriesAlbumWriter
import com.syrok0010.nextgallery.feature.albums.fileUrl
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.koin.android.ext.android.inject

@Serializable
data class UploadSelection(
    val contentUri: String,
    val displayName: String,
    val mimeType: String?,
    /** Own Photos album that receives the file after a successful upload. */
    val album: UploadAlbum? = null,
)

@Serializable
data class UploadAlbum(val owner: String, val name: String)

class UploadForegroundService : Service() {
    private val credentialsStore: CredentialsStore by inject()
    private val transport: NextcloudTransport by inject()
    private val albums: MemoriesAlbumWriter by inject()
    private val catalog: AlbumCatalogRepository by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager: NotificationManager by lazy {
        checkNotNull(
            getSystemService(),
        )
    }

    // Main thread only: starts arrive there, and the worker reads the queue there too.
    private val queue = ArrayDeque<List<UploadSelection>>()
    private var worker: Job? = null
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val selections = intent
            ?.getStringExtra(EXTRA_SELECTIONS)
            ?.let { payload ->
                runCatching { Json.decodeFromString<List<UploadSelection>>(payload) }.getOrNull()
            }.orEmpty()
        if (selections.isEmpty()) {
            // A running worker stops the service with the latest start id itself.
            if (worker == null) {
                // startForegroundService still requires startForeground, even right before stop.
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification(getString(R.string.upload_preparing), uploading = true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        queue += selections
        if (worker == null) {
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
     * Batches run one after another under one foreground notification. One result notification
     * collects every batch of this run, so results neither pile up nor replace a queued batch's.
     * The service leaves the foreground only when no batch is waiting.
     */
    private suspend fun drainQueue() {
        val results = mutableListOf<BatchResult>()
        while (true) {
            val batch = queue.removeFirstOrNull() ?: break
            results += runBatch(batch)
            notificationManager.notify(RESULT_NOTIFICATION_ID, resultNotification(results))
        }
        // No suspension from the empty check to here, so no start can slip in between.
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    private suspend fun runBatch(selections: List<UploadSelection>): BatchResult {
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
                    upload(selections, progress) { message, percent ->
                        updates.trySend(notification(message, uploading = true, progress = percent))
                    }
                }
            }
            publisher.cancelAndJoin()
            updates.close()
            result
        }
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        delay(500L)
        val message = result.fold(
            onSuccess = { getString(R.string.upload_done, progress.completed, selections.size) },
            onFailure = { getString(R.string.upload_stopped, failureReason(it)) },
        )
        if (result.isFailure) progress.notUploaded(selections.drop(progress.completed))
        val albumSummary = uploadAlbumSummaries(progress.albums)
            .joinToString(" · ") { it.text() }
            .ifEmpty { null }
        // The album sheet refreshed the catalog before these files reached the album.
        if (progress.albums.isNotEmpty()) catalog.refresh()
        return BatchResult(message, albumSummary)
    }

    private fun resultNotification(results: List<BatchResult>): Notification {
        val single = results.singleOrNull()
        if (single != null) return notification(single.title, text = single.details)
        val lines = results.map { listOfNotNull(it.title, it.details).joinToString(" · ") }
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle(getString(R.string.upload_batches_done, results.size))
            .setContentText(lines.last())
            .setStyle(Notification.InboxStyle().also { style -> lines.forEach(style::addLine) })
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun failureReason(error: Throwable): String =
        (error as? UploadException)?.let { getString(it.reason, *it.args) }
            ?: error.localizedMessage
            ?: getString(R.string.upload_error_network)

    private fun AlbumUploadSummary.text(): String =
        if (failed > 0) {
            getString(R.string.upload_album_summary_failed, album, added, failed)
        } else {
            getString(R.string.upload_album_summary, album, added)
        }

    private suspend fun upload(
        selections: List<UploadSelection>,
        progress: UploadProgress,
        onProgress: (String, Int?) -> Unit,
    ) {
        val credentials = credentialsStore.load()
            ?: throw UploadException(R.string.upload_error_session)
        // Login Flow may hand out an e-mail as login name; DAV paths use the uid.
        val user = albums.currentUser(credentials)
        val config = transport.memoriesApi(credentials).config()
        val destination = config.timelinePath?.takeIf { it.isNotBlank() }
            ?: throw UploadException(R.string.upload_error_timeline)
        selections.forEachIndexed { index, selection ->
            val message = getString(R.string.upload_progress, index + 1, selections.size)
            val uploaded = put(
                credentials = credentials,
                url = uploadUrl(credentials.serverUrl, user, destination, selection.displayName),
                selection = selection,
                onProgress = { progress -> onProgress(message, progress) },
            )
            progress.completed++
            selection.album?.let { album ->
                progress.record(album, addToAlbum(credentials, uploaded, album))
            }
        }
    }

    private suspend fun addToAlbum(
        credentials: AccountCredentials,
        uploaded: UploadedFile,
        album: UploadAlbum,
    ): AlbumCopyOutcome {
        // An existing file with the same name may be another photo; add it only if sizes match.
        if (uploaded.existed && !sameRemoteSize(credentials, uploaded)) {
            return AlbumCopyOutcome.Failed
        }
        return try {
            albums.addFile(
                credentials,
                uploaded.url,
                AlbumAddress(album.owner, album.name),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AlbumCopyOutcome.Failed
        }
    }

    private fun sameRemoteSize(credentials: AccountCredentials, uploaded: UploadedFile): Boolean {
        if (uploaded.length < 0L) return false
        val request = transport
            .authenticatedRequestBuilder(credentials, uploaded.url.toString(), accept = "*/*")
            .head()
            .build()
        return runCatching {
            transport.baseClient.newCall(request).execute().use { response ->
                response.isSuccessful &&
                    response.header("Content-Length")?.toLongOrNull() == uploaded.length
            }
        }.getOrDefault(false)
    }

    private fun put(
        credentials: AccountCredentials,
        url: HttpUrl,
        selection: UploadSelection,
        onProgress: (Int?) -> Unit,
    ): UploadedFile {
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
        return transport.baseClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 412) {
                throw UploadException(
                    R.string.upload_error_http,
                    response.code,
                    selection.displayName,
                )
            }
            UploadedFile(url, existed = response.code == 412, length = length)
        }
    }

    private fun notification(
        message: String,
        uploading: Boolean = false,
        progress: Int? = null,
        text: String? = null,
    ): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle(message)
            .apply { if (text != null) setContentText(text) }
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

    private class BatchResult(val title: String, val details: String?)

    private class UploadedFile(val url: HttpUrl, val existed: Boolean, val length: Long)

    private class UploadException(@StringRes val reason: Int, vararg val args: Any) : Exception()

    /** Survives a failed upload so earlier album results still reach the notification. */
    private class UploadProgress {
        var completed = 0
        val albums = linkedMapOf<String, MutableList<AlbumCopyOutcome>>()

        fun record(album: UploadAlbum, outcome: AlbumCopyOutcome) {
            albums.getOrPut(album.name) { mutableListOf() } += outcome
        }

        /** Files left after a failed upload did not reach their albums either. */
        fun notUploaded(selections: List<UploadSelection>) {
            selections.forEach { selection ->
                selection.album?.let { record(it, AlbumCopyOutcome.Failed) }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "cloud-upload"
        private const val FOREGROUND_NOTIFICATION_ID = 42

        private const val RESULT_NOTIFICATION_ID = 43
        private const val EXTRA_SELECTIONS = "selections"

        fun start(context: Context, selections: List<UploadSelection>) {
            if (selections.isEmpty()) return
            val payload = Json.encodeToString(selections)
            val intent = Intent(context, UploadForegroundService::class.java)
                .putExtra(EXTRA_SELECTIONS, payload)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

internal data class AlbumUploadSummary(val album: String, val added: Int, val failed: Int)

/** Album additions after upload, per album; files already in the album count as added. */
internal fun uploadAlbumSummaries(
    albums: Map<String, List<AlbumCopyOutcome>>,
): List<AlbumUploadSummary> =
    albums.map { (name, outcomes) ->
        val failed = outcomes.count { it == AlbumCopyOutcome.Failed }
        AlbumUploadSummary(name, added = outcomes.size - failed, failed = failed)
    }

/** DAV URL of an upload in the user's files; [user] is the Nextcloud uid, not the login name. */
internal fun uploadUrl(serverUrl: String, user: String, directory: String, name: String): HttpUrl =
    fileUrl(serverUrl, user, directory).newBuilder().addPathSegment(name).build()

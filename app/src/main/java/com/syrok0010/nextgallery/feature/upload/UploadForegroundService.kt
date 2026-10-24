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
import com.syrok0010.nextgallery.feature.timeline.remote.memoriesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager: NotificationManager by lazy {
        checkNotNull(
            getSystemService(),
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val payload = intent?.getStringExtra(EXTRA_SELECTIONS) ?: return START_NOT_STICKY
        val selections = runCatching {
            Json.decodeFromString<List<UploadSelection>>(payload)
        }.getOrElse {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (selections.isEmpty()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            notification("Подготовка загрузки", uploading = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        serviceScope.launch {
            // Android drops bursts of notification updates, including the terminal result.
            // Keep only the latest progress and leave room for foreground/final updates.
            val updates = Channel<Notification>(Channel.CONFLATED)
            val publisher = launch {
                for (update in updates) {
                    notificationManager.notify(NOTIFICATION_ID, update)
                    delay(500L)
                }
            }
            val progress = UploadProgress()
            val result = runCatching {
                upload(selections, progress) { message, percent ->
                    updates.trySend(notification(message, uploading = true, progress = percent))
                }
            }
            publisher.cancelAndJoin()
            updates.close()
            delay(500L)
            val message = result.fold(
                onSuccess = { "Загрузка завершена: ${progress.completed} из ${selections.size}" },
                onFailure = { "Загрузка остановлена: ${it.localizedMessage ?: "ошибка сети"}" },
            )
            if (result.isFailure) progress.notUploaded(selections.drop(progress.completed))
            val albumSummary = uploadAlbumSummary(progress.albums)
            // The album sheet refreshed the catalog before these files reached the album.
            if (progress.albums.isNotEmpty()) catalog.refresh()
            // Keep the final result visible after the foreground service stops.
            stopForeground(STOP_FOREGROUND_DETACH)
            notificationManager.notify(NOTIFICATION_ID, notification(message, text = albumSummary))
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun upload(
        selections: List<UploadSelection>,
        progress: UploadProgress,
        onProgress: (String, Int?) -> Unit,
    ) {
        val credentials = credentialsStore.load()
            ?: error("Сессия Nextcloud больше недоступна")
        val config = transport.memoriesApi(credentials).config()
        val destination = config.timelinePath?.takeIf { it.isNotBlank() }
            ?: error("Memories не сообщил папку таймлайна")
        selections.forEachIndexed { index, selection ->
            val message = "Загрузка: ${index + 1} из ${selections.size}"
            val uploaded = put(
                credentials = credentials,
                destination = destination,
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
                uploaded.url.toHttpUrl(),
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
            .authenticatedRequestBuilder(credentials, uploaded.url, accept = "*/*")
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
        destination: String,
        selection: UploadSelection,
        onProgress: (Int?) -> Unit,
    ): UploadedFile {
        val uri = Uri.parse(selection.contentUri)
        val url =
            webDavUrl(
                credentials.serverUrl,
                credentials.loginName,
                destination,
                selection.displayName,
            )
        val length = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        onProgress(if (length >= 0L) 0 else null)
        val body = UploadRequestBody(
            length = length,
            mediaType = selection.mimeType?.toMediaTypeOrNull(),
            openStream = {
                contentResolver.openInputStream(
                    uri,
                ) ?: error("Не удалось открыть локальную копию $uri")
            },
            onProgress = onProgress,
        )
        val request = transport
            .authenticatedRequestBuilder(credentials, url, accept = "*")
            .method("PUT", body)
            .header("If-None-Match", "*")
            .build()
        return transport.baseClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 412) {
                error("HTTP ${response.code} при загрузке ${selection.displayName}")
            }
            UploadedFile(url, existed = response.code == 412, length = length)
        }
    }

    private fun webDavUrl(
        serverUrl: String,
        login: String,
        directory: String,
        name: String,
    ): String {
        val base = NextcloudTransport.normalizeServerOrigin(serverUrl).trimEnd('/')
        val encodedLogin = Uri.encode(login)
        val segments = (
            listOf("remote.php", "dav", "files", encodedLogin) +
                directory.trim('/').split('/').filter(String::isNotBlank) +
                name
            ).joinToString("/") { segment ->
            if (segment == encodedLogin) segment else Uri.encode(segment)
        }
        return "$base/$segments"
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
                "Загрузка в облако",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class UploadedFile(val url: String, val existed: Boolean, val length: Long)

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
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_SELECTIONS = "selections"

        fun start(context: Context, selections: List<UploadSelection>) {
            val payload = Json.encodeToString(selections)
            val intent = Intent(context, UploadForegroundService::class.java)
                .putExtra(EXTRA_SELECTIONS, payload)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/** Notification text for album additions after upload; files already in the album count as added. */
internal fun uploadAlbumSummary(albums: Map<String, List<AlbumCopyOutcome>>): String? =
    albums.entries
        .joinToString(" · ") { (name, outcomes) ->
            val failed = outcomes.count { it == AlbumCopyOutcome.Failed }
            val added = outcomes.size - failed
            buildString {
                append("В «$name»: $added")
                if (failed > 0) append(", не добавлено: $failed")
            }
        }.ifEmpty { null }

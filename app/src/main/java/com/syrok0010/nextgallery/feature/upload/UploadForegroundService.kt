package com.syrok0010.nextgallery.feature.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.feature.timeline.remote.memoriesApi
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.koin.android.ext.android.inject

@Serializable
data class UploadSelection(val contentUri: String, val displayName: String, val mimeType: String?)

class UploadForegroundService : Service() {
    private val credentialsStore: CredentialsStore by inject()
    private val transport: NextcloudTransport by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        startForegroundCompat(notification("Подготовка загрузки", uploading = true))
        serviceScope.launch {
            // Android drops bursts of notification updates, including the terminal result.
            // Keep only the latest progress and leave room for foreground/final updates.
            val updates = Channel<Notification>(Channel.CONFLATED)
            val manager = getSystemService(NotificationManager::class.java)
            val publisher = launch {
                for (update in updates) {
                    manager.notify(NOTIFICATION_ID, update)
                    delay(500L)
                }
            }
            val result = runCatching {
                upload(selections) { message, progress ->
                    updates.trySend(notification(message, uploading = true, progress = progress))
                }
            }
            publisher.cancelAndJoin()
            updates.close()
            delay(500L)
            val message = result.fold(
                onSuccess = { "Загрузка завершена: ${it.completed} из ${selections.size}" },
                onFailure = { "Загрузка остановлена: ${it.localizedMessage ?: "ошибка сети"}" },
            )
            // Keep the final result visible after the foreground service stops.
            stopForeground(STOP_FOREGROUND_DETACH)
            manager.notify(NOTIFICATION_ID, notification(message))
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun upload(
        selections: List<UploadSelection>,
        onProgress: (String, Int?) -> Unit,
    ): UploadResult {
        val credentials = credentialsStore.load()
            ?: error("Сессия Nextcloud больше недоступна")
        val config = transport.memoriesApi(credentials).config()
        val destination = config.timelinePath?.takeIf { it.isNotBlank() }
            ?: error("Memories не сообщил папку таймлайна")
        var completed = 0
        selections.forEachIndexed { index, selection ->
            val message = "Загрузка: ${index + 1} из ${selections.size}"
            put(
                credentials = credentials,
                destination = destination,
                selection = selection,
                onProgress = { progress -> onProgress(message, progress) },
            )
            completed++
        }
        return UploadResult(completed)
    }

    private fun put(
        credentials: com.syrok0010.nextgallery.core.session.AccountCredentials,
        destination: String,
        selection: UploadSelection,
        onProgress: (Int?) -> Unit,
    ) {
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
        transport.baseClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 412) {
                error("HTTP ${response.code} при загрузке ${selection.displayName}")
            }
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

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
            .setContentTitle(message)
            .setOngoing(uploading)
            .setOnlyAlertOnce(true)
            .apply { if (uploading) setProgress(100, progress ?: 0, progress == null) }
            .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
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

    private data class UploadResult(val completed: Int)

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

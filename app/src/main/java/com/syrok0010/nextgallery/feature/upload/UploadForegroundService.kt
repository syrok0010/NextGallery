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
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
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

        startForegroundCompat(notification("Подготовка загрузки", 0, selections.size))
        serviceScope.launch {
            val result = runCatching { upload(selections) }
            val message = result.fold(
                onSuccess = { "Загрузка завершена: ${it.completed} из ${selections.size}" },
                onFailure = { "Загрузка остановлена: ${it.localizedMessage ?: "ошибка сети"}" },
            )
            notify(message, if (result.isSuccess) 100 else 0)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun upload(selections: List<UploadSelection>): UploadResult {
        val credentials = credentialsStore.load()
            ?: error("Сессия Nextcloud больше недоступна")
        val config = transport.memoriesApi(credentials).config()
        val destination = config.timelinePath?.takeIf { it.isNotBlank() }
            ?: error("Memories не сообщил папку таймлайна")
        var completed = 0
        selections.forEachIndexed { index, selection ->
            put(
                credentials = credentials,
                destination = destination,
                selection = selection,
            )
            completed++
            notify(
                "Загрузка: ${index + 1} из ${selections.size}",
                (index + 1) * 100 / selections.size,
            )
        }
        return UploadResult(completed)
    }

    private fun put(
        credentials: com.syrok0010.nextgallery.core.session.AccountCredentials,
        destination: String,
        selection: UploadSelection,
    ) {
        val uri = Uri.parse(selection.contentUri)
        val url =
            webDavUrl(
                credentials.serverUrl,
                credentials.loginName,
                destination,
                selection.displayName,
            )
        val body = ContentUriRequestBody(
            resolver = contentResolver,
            uri = uri,
            mediaType = selection.mimeType?.toMediaTypeOrNull(),
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

    private fun notify(message: String, progress: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message, progress, 100))
    }

    private fun notification(message: String, progress: Int, max: Int): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle("NextGallery")
            .setContentText(message)
            .setOngoing(progress in 1 until 100)
            .setProgress(max, progress, progress == 0)
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

    private class ContentUriRequestBody(
        private val resolver: android.content.ContentResolver,
        private val uri: Uri,
        private val mediaType: okhttp3.MediaType?,
    ) : RequestBody() {
        override fun contentType() = mediaType

        override fun contentLength(): Long =
            resolver.openAssetFileDescriptor(uri, "r")?.use {
                it.length
            } ?: -1L

        override fun writeTo(sink: BufferedSink) {
            resolver.openInputStream(uri)?.use { input ->
                input.copyTo(sink.outputStream())
            } ?: throw IOException("Не удалось открыть локальную копию $uri")
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

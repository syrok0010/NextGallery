package com.syrok0010.nextgallery.feature.upload

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.media.*
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in crash fixture. Never runs in ordinary connected tests and never embeds credentials. */
class UploadProcessDeathEvidence {
    @Test fun stageRealDavCrashWindows(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("durableEvidence") == "seed")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.syrok0010.nextgallery.automation")
        val secretFile = File(context.getExternalFilesDir(null), "durable156-credentials.json")
        val credentials = Json.decodeFromString<AccountCredentials>(secretFile.readText())
        check(credentials.serverUrl == "http://test.syrok/" && credentials.userId == "test")
        KeystoreCredentialsStore(context, Json).save(credentials)
        secretFile.delete()
        val sessions = SessionStore(object : CredentialsStore {
            override fun load() = credentials
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        })
        val source = UploadSource(context.contentResolver)
        val db = UploadQueueDatabase.open(context)
        val journal = UploadJournal(db)
        val library = MediaLibraryIndex()
        val names = listOf("cancelled", "receipt", "uncertain", "pending")
        val selections = names.map { name ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "durable156-$name.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/durable156/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { output ->
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.rgb(20 + names.indexOf(name), 100, 150))
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                bitmap.recycle()
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            UploadSelection(uri.toString(), "durable156-$name.jpg", "image/jpeg")
        }
        val ids = selections.mapIndexed { index, file ->
            MediaItem(MediaId("durable156-$index"), 20758, file.displayName, file.mimeType, 32, 32, null, null, null, null, null,
                1793512800, false, null, false, false, MediaAssetRef.LocalContent(file.contentUri, null))
        }
        library.publish(ids)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = UploadQueue(sessions, library, scope, journal, source::snapshot, { _, _, _ -> true }, { _, _, _, _ -> UploadStepResult("Done") }, {})
        check(queue.submit(ids.map { it.mediaId }, requestId = "durable156-process-kill") is EnqueueResult.Accepted)
        val rows = journal.jobs(credentials.uploadOwner)
        journal.cancel(credentials.uploadOwner, setOf(rows[0].id))
        val transport = NextcloudTransport(Json)
        for (index in 1..2) {
            val job = journal.claim(rows[index].id, credentials.uploadOwner, "fixture-before-process-kill")!!
            val file = job.selection
            val url = journal.destination(job, "http://test.syrok/remote.php/dav/files/test/Photos/${file.displayName}")!!.destination!!.toHttpUrl()
            val body = UploadRequestBody(file.stamp!!.size, "image/jpeg".toMediaType(),
                { context.contentResolver.openInputStream(android.net.Uri.parse(file.contentUri))!! }, {})
            val uploaded = withContext(Dispatchers.IO) { uploadFile(transport, credentials, url, file, body) }!!
            if (index == 1) check(journal.accept(job, UploadReceipt(url.toString(), uploaded.created, uploaded.fileId)) != null)
            // index 2 deliberately dies after server response and before local receipt.
        }
        Log.i("DurableUploadEvidence", "READY cancelled + accepted receipt + uncertain server PUT + pending; kill this automation process with SIGKILL")
        awaitCancellation()
    }
}

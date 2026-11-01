package com.syrok0010.nextgallery.feature.upload

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.database.NextGalleryDatabase
import com.syrok0010.nextgallery.core.database.RoomMediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.*
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.albums.*
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.*
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesFiles
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in disposable stand checks. Credentials arrive in a private file, never in the APK. */
class DurableAlbumLiveEvidence {
    @Test fun registeredLocalUploadLinkAndReconciledAlbum(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("durable158")
        assumeTrue(mode in setOf("flow", "stage", "attention", "verify", "cleanup"))
        val context = instrumentation.targetContext
        check(context.packageName == "com.syrok0010.nextgallery.automation")
        val directory = context.getExternalFilesDir(null)!!
        val credentialFile = File(directory, "durable158-credentials.json")
        val secure = KeystoreCredentialsStore(context, Json)
        val account = if (credentialFile.exists()) Json.decodeFromString<AccountCredentials>(credentialFile.readText()) else secure.load()!!
        check(account.serverUrl == "http://test.syrok/" && account.userId == "test")
        credentialFile.delete()
        val sessions = SessionStore(object : CredentialsStore {
            override fun load() = account
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        })
        // The real cache/identity schema and Android reader assign the local MediaId.
        val identityDb = Room.databaseBuilder(context, NextGalleryDatabase::class.java, "next-gallery.db").build()
        val identities = RoomMediaIdentityRegistry(identityDb)
        val db = UploadQueueDatabase.open(context)
        val journal = UploadJournal(db)
        val trace = File(directory, "durable158-http.log")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            trace.appendText("${request.method} ${request.url} ${response.code}\n")
            response
        }.build()
        val transport = NextcloudTransport(Json { ignoreUnknownKeys = true }, client)
        val step = DurableAlbumStep(transport)
        val writer = MemoriesAlbumWriter(transport, MemoriesFiles(transport))
        val manifest = File(directory, "durable158-manifest.txt")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            if (mode == "cleanup") {
                val jobs = journal.jobs(account.uploadOwner).filter { it.selection.displayName.startsWith("durable158-") }
                journal.cancel(account.uploadOwner, jobs.map { it.id }.toSet())
                journal.clearFinished(account.uploadOwner)
                // Host cleanup uses adb MediaStore on exactly these task-prefixed rows;
                // instrumentation may lose MediaStore ownership across separate runs.
                check(journal.jobs(account.uploadOwner).all { it.selection.displayName.startsWith("durable158-") })
                check(journal.revokeIfFinished(account.uploadOwner))
                secure.clear()
                return@runBlocking
            }
            if (mode == "verify") {
                val saved = manifest.readLines().last().split('|')
                val job = journal.jobs(account.uploadOwner).single { it.id == saved[0] }
                assertEquals("Done", job.albumStep)
                assertEquals("Done", job.linkStep)
                assertEquals(saved[1], job.selection.mediaId)
                assertEquals(saved[2].toLong(), job.receipt!!.fileId)
                val resolved = identities.resolve(listOf(mediaIdentityCandidate(MediaSourceIdentity(MediaSourceKind.Memories, saved[2]), auid = null, buid = null)))
                assertEquals(saved[1], resolved.mediaIds.values.single().value)
                Log.i("Durable158Evidence", "VERIFIED job=${job.id} MediaId=${saved[1]} fileId=${saved[2]} no pending steps")
                return@runBlocking
            }
            if (mode == "attention") {
                val previous = journal.jobs(account.uploadOwner)
                check(previous.all { it.selection.displayName.startsWith("durable158-") })
                journal.cancel(account.uploadOwner, previous.map { it.id }.toSet())
                journal.clearFinished(account.uploadOwner)
            }
            secure.save(account)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.READ_MEDIA_IMAGES")
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.READ_MEDIA_VIDEO")
            val suffix = InstrumentationRegistry.getArguments().getString("durable158Run") ?: mode!!
            val name = "durable158-$suffix.jpg"
            val album = "durable158-$suffix"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/durable158/")
                put(MediaStore.Images.Media.DATE_TAKEN, InstrumentationRegistry.getArguments().getString("durable158EpochMillis")?.toLong() ?: when (mode) { "stage" -> 1793516400000L; "attention" -> 1793520000000L; else -> 1793512800000L })
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { output ->
                Bitmap.createBitmap(InstrumentationRegistry.getArguments().getString("durable158Width")?.toInt()
                    ?: when (mode) { "stage" -> 128; "attention" -> 192; else -> 64 }, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.rgb(40, 160, 100))
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                    bitmap.recycle()
                }
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val localSource = LocalMediaSource(AndroidMediaStoreReader(context.contentResolver, identityDb.localMediaMetadataDao()),
                LocalMediaProjectionRepository(identityDb), identities, LocalMediaChangeObserver { emptyFlow() })
            val indexed = withTimeout(15_000) { localSource.updates().first { it.processed != null && it.progress == null && it.items.any { item -> item.displayName == name } } }
            val item = indexed.items.single { it.displayName == name }
            val library = MediaLibraryIndex().apply { publish(indexed.items) }
            writer.create(account, album)
            val queue = UploadQueue(sessions, library, scope, journal, UploadSource(context.contentResolver)::snapshot,
                { credentials, local, id -> UploadedCopiesFollowUp(identities, sessions).link(credentials, local, id) },
                step::run, {}, "durable158-live", step::snapshot)
            assertTrue(queue.submit(listOf(item.mediaId), listOf(UploadIntent("album", owner = "test", target = album)), "durable158-$suffix") is EnqueueResult.Accepted)
            val batch = queue.waiting.value.single()
            check(batch.items.single().selection.displayName == name) { "Fixture aliases collided with an existing local copy; use distinct real bytes/EXIF, never override identity" }
            var puts = 0
            queue.runBatch(batch) { credentials, upload, claim ->
                puts++
                val url = queue.pinDestination(claim, "http://test.syrok/remote.php/dav/files/test/Photos/$name")!!.toHttpUrl()
                val selection = upload.selection
                val body = UploadRequestBody(selection.stamp!!.size, "image/jpeg".toMediaType(), { context.contentResolver.openInputStream(Uri.parse(selection.contentUri))!! }, {})
                withContext(Dispatchers.IO) { uploadFile(transport, credentials, url, selection, body, upload::execute) }
            }
            assertEquals(1, puts)
            var row = journal.jobs(account.uploadOwner).single { it.id == batch.items.single().id }
            assertEquals("Done", row.linkStep)
            assertEquals("Attention", row.albumStep)
            assertEquals("ReplayUnsupported", uploadJson.decodeFromString<UploadStepResult>(row.albumResultJson!!).problem)
            val receipt = row.receipt!!
            val remote = MediaSourceIdentity(MediaSourceKind.Memories, receipt.fileId.toString())
            assertEquals(item.mediaId, identities.resolve(listOf(mediaIdentityCandidate(remote, auid = null, buid = null))).mediaIds[remote])
            assertTrue(row.intents.single { it.kind == "album" }.albumId!! > 0)
            manifest.appendText("${row.id}|${item.mediaId.value}|${receipt.fileId}|$uri|$album|${receipt.url}\n")
            if (mode == "attention") {
                Log.i("Durable158Evidence", "ATTENTION uploaded=${row.receipt} album=${row.albumResultJson}")
                return@runBlocking
            }
            // Explicit separate addition uses the existing production writer. The durable runner
            // deliberately cannot issue this COPY: DAV cannot condition it on the collection id.
            if (mode == "stage") {
                journal.retrySteps(account.uploadOwner, row.id)
                val claim = journal.claim(row.id, account.uploadOwner, "durable158-crash")!!
                val running = journal.beginStep(claim, "album")!!
                assertTrue(journal.stepCurrent(running, "album"))
                assertEquals(AlbumCopyOutcome.Added, writer.addFile(account, receipt.url.toHttpUrl(), AlbumAddress("test", album)))
                Log.i("Durable158Evidence", "READY real server COPY, persisted Running, no step commit; SIGKILL automation now")
                awaitCancellation()
            }
            assertEquals(AlbumCopyOutcome.Added, writer.addFile(account, receipt.url.toHttpUrl(), AlbumAddress("test", album)))
            queue.retryActions(batch.items.single())
            withTimeout(5_000) { queue.waiting.first { it.isNotEmpty() } }
            queue.runBatch(batch) { _, _, _ -> error("Step retry must not PUT") }
            row = journal.jobs(account.uploadOwner).single { it.id == row.id }
            assertEquals("Done", row.albumStep)
            assertEquals(receipt, row.receipt)
            assertEquals(1, puts)
            assertEquals(item.mediaId, identities.resolve(listOf(mediaIdentityCandidate(remote, auid = null, buid = null))).mediaIds[remote])
            Log.i("Durable158Evidence", "FLOW registered MediaStore uri=$uri MediaId=${item.mediaId} receipt=$receipt exact album membership=${row.albumResultJson}; PUT=1")
        } finally {
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            db.close(); identityDb.close()
        }
    }
}

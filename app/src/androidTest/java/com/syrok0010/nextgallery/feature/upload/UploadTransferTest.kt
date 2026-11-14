package com.syrok0010.nextgallery.feature.upload

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.loopbackServer
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.network.origin
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One file from the phone to the timeline folder over the real DAV, Memories and capabilities
 * clients. The server is MockWebServer; the phone file comes from a test [ContentProvider].
 */
class UploadTransferTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "upload-transfer-test.db"
    private val photo = File(context.cacheDir, "upload-transfer-photo.jpg")
    private val requests = CopyOnWriteArrayList<String>()

    /** What the server answers instead of the happy path; null falls through to it. */
    @Volatile private var override: (RecordedRequest) -> MockResponse? = { null }

    @Volatile private var timelinePath = "/Photos"

    @Volatile private var partSize = 0L

    @Volatile private var phoneRefuses: Exception? = null
    private lateinit var server: MockWebServer
    private lateinit var db: UploadQueueDatabase
    private lateinit var journal: UploadJournal
    private lateinit var scope: CoroutineScope
    private lateinit var credentials: AccountCredentials
    private lateinit var queue: UploadQueue
    private lateinit var transfer: UploadTransfer
    private var jobs = 0

    @Before fun setup() {
        photo.writeBytes(ByteArray(PHOTO_SIZE) { it.toByte() })
        server = loopbackServer { request ->
            requests += "${request.method} ${request.target}"
            override(request) ?: happyPath(request)
        }
        credentials = AccountCredentials(server.origin, "test", "password", "test")
        val sessions = SessionStore(
            object : CredentialsStore {
                override fun load() = credentials

                override fun save(credentials: AccountCredentials) = Unit
            },
        )
        context.deleteDatabase(dbName)
        db = UploadQueueDatabase.open(context, dbName)
        journal = UploadJournal(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        queue = UploadQueue(
            sessions,
            MediaLibraryIndex(),
            scope,
            journal,
            { SourceStamp(1, 1, 1) },
            UploadFollowUps(link = { _, _, _, _ -> true }, album = { _, _, _, _ -> true }),
            {},
        )
        transfer = newTransfer()
    }

    /** A transfer serves one pass: it reads the folder and the part size once. */
    private fun newTransfer(): UploadTransfer {
        val transport = NextcloudTransport(Json { ignoreUnknownKeys = true })
        return UploadTransfer(
            ContentResolver.wrap(PhoneFiles()),
            transport,
            MemoriesApiClient(transport) { credentials },
            queue,
        )
    }

    @After fun cleanup() =
        runBlocking {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
            context.deleteDatabase(dbName)
            server.close()
            photo.delete()
            Unit
        }

    @Test fun phoneFileThatCannotBeReadIsAProblemWithTheSourceNotARetry() =
        runBlocking {
            for (refusal in listOf(
                FileNotFoundException("deleted"),
                SecurityException("permission revoked"),
            )) {
                phoneRefuses = refusal

                val error = failure()

                assertEquals(
                    refusal.toString(),
                    UploadProblem.Source,
                    (error as UploadException).problem,
                )
                assertFalse(error.temporary)
            }
        }

    @Test fun lostConnectionIsTemporary() =
        runBlocking {
            server.close()

            val error = failure()

            assertFalse(error.toString(), error is UploadException)
            assertTrue(error.temporary)
        }

    @Test fun missingTimelineFolderIsAFolderProblem() =
        runBlocking {
            for (code in listOf(404, 409)) {
                override = {
                    if (it.method == "PUT") {
                        MockResponse
                            .Builder()
                            .code(
                                code,
                            ).build()
                    } else {
                        null
                    }
                }

                val error = failure() as UploadException

                assertEquals(UploadProblem.Directory to code, error.problem to error.httpStatus)
                assertFalse(error.temporary)
            }
        }

    @Test fun otherServerAnswersKeepTheirStatusAndOnlyBusyOnesAreRetried() =
        runBlocking {
            for ((code, temporary) in listOf(
                400 to false,
                403 to false,
                500 to true,
                503 to true,
                423 to true,
            )) {
                override = {
                    if (it.method == "PUT") {
                        MockResponse
                            .Builder()
                            .code(
                                code,
                            ).build()
                    } else {
                        null
                    }
                }

                val error = failure() as UploadException

                assertEquals(UploadProblem.Http to code, error.problem to error.httpStatus)
                assertEquals("HTTP $code", temporary, error.temporary)
            }
        }

    @Test fun memoriesFailureAndATimelineWithoutAFolderAreReportedAsSuch() =
        runBlocking {
            override = {
                if ("memories" in it.target) {
                    MockResponse
                        .Builder()
                        .code(
                            500,
                        ).build()
                } else {
                    null
                }
            }
            val memories = failure() as UploadException
            assertEquals(UploadProblem.Memories to 500, memories.problem to memories.httpStatus)

            override = { null }
            timelinePath = " ; "
            val timeline = failure() as UploadException
            assertEquals(UploadProblem.Timeline, timeline.problem)
            assertFalse(requests.any { it.startsWith("PUT") })
        }

    @Test fun capabilitiesFailureKeepsItsStatus() =
        runBlocking {
            override = {
                if ("capabilities" in it.target) {
                    MockResponse
                        .Builder()
                        .code(
                            503,
                        ).build()
                } else {
                    null
                }
            }

            val error = failure() as UploadException

            assertEquals(UploadProblem.Http to 503, error.problem to error.httpStatus)
            assertTrue(error.temporary)
        }

    @Test fun onlyAFileLargerThanOnePartIsSentInParts() =
        runBlocking {
            for (size in listOf(0L, PHOTO_SIZE.toLong(), PHOTO_SIZE + 1L)) {
                partSize = size
                transfer = newTransfer()
                requests.clear()

                assertNotNull(transfer.upload(credentials, claim()) {})

                assertEquals("part size $size", listOf("PUT $PHOTO_PATH"), uploads())
            }

            partSize = 4
            transfer = newTransfer()
            requests.clear()
            val uploaded = transfer.upload(credentials, claim()) {}

            assertEquals(
                PHOTO_PATH,
                "/remote.php/dav/files/test/" + uploaded!!.path.segments.joinToString("/"),
            )
            val upload = uploads().first().substringAfter("MKCOL ")
            assertTrue(upload, upload.startsWith("/remote.php/dav/uploads/test/"))
            assertEquals(
                listOf(
                    "MKCOL $upload",
                    "PUT $upload/1",
                    "PUT $upload/2",
                    "PUT $upload/3",
                    "MOVE $upload/.file",
                ),
                uploads(),
            )
        }

    /** Requests that create something on the server, without the reads around them. */
    private fun uploads() =
        requests.filter { it.substringBefore(' ') in setOf("PUT", "MKCOL", "MOVE") }

    private suspend fun failure(): Exception {
        try {
            transfer.upload(credentials, claim()) {}
        } catch (error: Exception) {
            return error
        }
        throw AssertionError("The upload succeeded")
    }

    /** A fresh job claimed by this test, so the transfer pins its own name. */
    private suspend fun claim(): UploadJob {
        val selection = UploadSelection(
            MediaAssetRef.LocalContent("content://phone/photo/${++jobs}", 1, 1),
            "photo.jpg",
            "image/jpeg",
        )
        journal.enqueue(listOf(selection), album = null)
        return journal.claimNext("test")!!
    }

    private fun happyPath(request: RecordedRequest): MockResponse =
        when {
            "apps/memories/api/config" in request.target ->
                json("""{"version":"10.1.0","timeline_path":"$timelinePath"}""")

            "cloud/capabilities" in request.target ->
                json(
                    """{"ocs":{"data":{"capabilities":{"files":{"chunked_upload":{"max_size":$partSize}}}}}}""",
                )

            request.method == "PROPFIND" -> MockResponse.Builder().code(404).build()

            request.method == "PUT" || request.method == "MKCOL" || request.method == "MOVE" ->
                MockResponse.Builder().code(201).addHeader("OC-FileId", "00000042ocabcdef").build()

            else -> MockResponse.Builder().code(500).build()
        }

    private fun json(body: String) =
        MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    /** The phone's media: [photo] under every URI, unless [phoneRefuses] says otherwise. */
    private inner class PhoneFiles : ContentProvider() {
        override fun onCreate() = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            phoneRefuses?.let { throw it }
            return ParcelFileDescriptor.open(photo, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri) = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    private companion object {
        const val PHOTO_SIZE = 10
        const val PHOTO_PATH = "/remote.php/dav/files/test/Photos/photo.jpg"
    }
}

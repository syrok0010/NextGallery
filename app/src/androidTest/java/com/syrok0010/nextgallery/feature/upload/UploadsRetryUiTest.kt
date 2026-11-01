package com.syrok0010.nextgallery.feature.upload

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.app.ui.UploadsScreen
import com.syrok0010.nextgallery.core.media.*
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.*
import org.junit.Assert.*

class UploadsRetryUiTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "durable158-ui.db"
    private lateinit var db: UploadQueueDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var queue: UploadQueue
    private val id = MediaId("ui-local")
    private var linked = false
    private var puts = 0

    @Before fun setup() {
        context.deleteDatabase(name)
        db = UploadQueueDatabase.open(context, name)
        val account = AccountCredentials("http://test.syrok", "test", "fixture", "test")
        val sessions = SessionStore(object : CredentialsStore {
            override fun load() = account
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        })
        val library = MediaLibraryIndex().apply {
            publish(
                listOf(
                    MediaItem(
                        id, 1, "retry.jpg", "image/jpeg", null, null, null, null, null, null, null,
                        1, false, null, false, false,
                        MediaAssetRef.LocalContent(
                            "content://media/external/images/media/42",
                            10,
                        ),
                    ),
                ),
            )
        }
        queue = UploadQueue(
            sessions,
            library,
            scope,
            UploadJournal(db),
            { SourceStamp(10, 1, 1) },
            { _, _, _ -> linked },
            { _, _, _, _, _ -> UploadStepResult("Done") },
            {},
        )
    }

    @After fun cleanup() =
        runBlocking {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            db.close()
            context.deleteDatabase(name)
            Unit
        }

    @Test fun uploadedRemainsVisibleAndRetryRunsOnlyTheAction() =
        runBlocking {
            queue.submit(listOf(id))
            val batch = queue.waiting.value.single()
            queue.runBatch(batch) { _, item, _ ->
                puts++
                UploadedFile(
                    item.selection,
                    "http://test.syrok/remote.php/dav/files/test/Photos/retry.jpg".toHttpUrl(),
                    true,
                    42,
                )
            }
            rule.setContent { MaterialTheme { UploadsScreen({}, queue) } }
            rule.onNodeWithText("Загружен").assertIsDisplayed()
            rule
                .onNodeWithText(
                    "Не удалось связать копии. Можно повторить действие без загрузки файла.",
                ).assertIsDisplayed()
            linked = true
            rule.onNodeWithText("Повторить действие").performClick()
            withTimeout(5_000) { queue.waiting.first { it.isNotEmpty() } }
            queue.runBatch(batch) { _, _, _ -> error("No PUT from UI retry") }
            rule.onNodeWithText("Загружен").assertIsDisplayed()
            rule.onNodeWithText("Повторить действие").assertDoesNotExist()
            assertEquals(1, puts)
        }

    @Test fun explicitCancellationOfUnresolvedStepPreservesUploadedResult() =
        runBlocking {
            queue.submit(listOf(id))
            val batch = queue.waiting.value.single()
            queue.runBatch(
                batch,
            ) { _, item, _ ->
                UploadedFile(
                    item.selection,
                    "http://test.syrok/source.jpg".toHttpUrl(),
                    true,
                    42,
                )
            }
            rule.setContent { MaterialTheme { UploadsScreen({}, queue) } }
            rule.onNodeWithText("Отменить", useUnmergedTree = true).performClick()
            rule.waitUntil(5_000) { !batch.items.single().state.value.pendingAction }
            rule.onNodeWithText("Загружен").assertIsDisplayed()
            rule
                .onNodeWithText(
                    "Действие отменено; загруженный файл остаётся в облаке.",
                ).assertIsDisplayed()
            rule.onNodeWithText("Повторить действие").assertDoesNotExist()
        }
}

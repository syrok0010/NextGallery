package com.syrok0010.nextgallery.feature.upload

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The fixture is the exported production v1 schema, not a v2 DAO pretending to be migration. */
class UploadJournalMigrationTest {
    @Test fun v1PendingAcceptedCancelledAndLegacyTargetSurviveProductionMigrationAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "durable158-v1-migration.db"
        context.deleteDatabase(name)
        context.getDatabasePath(name).parentFile!!.mkdirs()
        val receipt = UploadReceipt("http://test.syrok/remote.php/dav/files/test/Photos/v1.jpg", true, 42)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
            old.execSQL("""CREATE TABLE IF NOT EXISTS `upload_jobs` (`id` TEXT NOT NULL, `batchId` TEXT NOT NULL, `owner` TEXT NOT NULL, `requestId` TEXT NOT NULL, `position` INTEGER NOT NULL, `selectionJson` TEXT NOT NULL, `intentsJson` TEXT NOT NULL, `state` TEXT NOT NULL, `destination` TEXT, `receiptJson` TEXT, `linkStep` TEXT NOT NULL, `albumStep` TEXT NOT NULL, `problem` TEXT, `httpStatus` INTEGER, `passId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `executor` TEXT, `attempts` INTEGER NOT NULL, `policyVersion` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, `seen` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
            old.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_upload_jobs_owner_requestId_position` ON `upload_jobs` (`owner`, `requestId`, `position`)""")
            old.execSQL("""CREATE TABLE IF NOT EXISTS `upload_owner` (`slot` INTEGER NOT NULL, `token` TEXT, PRIMARY KEY(`slot`))""")
            old.execSQL("INSERT INTO upload_owner(slot, token) VALUES(1, 'owner')")
            listOf("Pending", "Accepted", "Cancelled").forEachIndexed { position, state ->
                old.execSQL("""INSERT INTO upload_jobs(id,batchId,owner,requestId,position,selectionJson,intentsJson,state,destination,receiptJson,linkStep,albumStep,problem,httpStatus,passId,revision,executor,attempts,policyVersion,hidden,seen) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", arrayOf(
                    "v1-$state", "batch-$state", "owner", "request-$state", position,
                    uploadJson.encodeToString(UploadSelection("content://media/external/images/media/42", "v1.jpg", "image/jpeg", stamp = SourceStamp(10, 1, 2))),
                    uploadJson.encodeToString(listOf(UploadIntent("link"), UploadIntent("album", owner = "test", target = "old-album"))),
                    state, receipt.url, if (state == "Accepted") uploadJson.encodeToString(receipt) else null,
                    if (state == "Accepted") "Pending" else "None", if (state == "Accepted") "Running" else "None",
                    null, null, "pass", 3, if (state == "Accepted") "old-process" else null, 1, 1, 0, 1,
                ))
            }
            old.version = 1
        }
        fun open() = UploadQueueDatabase.open(context, name)
        var db = open()
        try {
            var journal = UploadJournal(db)
            journal.activate("owner"); journal.recover("owner", "new-process")
            val migrated = journal.jobs("owner")
            assertEquals(3, migrated.size)
            assertEquals("Pending", migrated[0].state)
            assertEquals(receipt, migrated[1].receipt)
            assertEquals("Attention", migrated[1].albumStep)
            assertEquals("LegacyTarget", uploadJson.decodeFromString<UploadStepResult>(migrated[1].albumResultJson!!).problem)
            assertEquals("Cancelled", migrated[2].state)
            assertTrue(migrated[0].seen); assertFalse(migrated[1].seen); assertTrue(migrated[2].seen)
            journal.retrySteps("owner", migrated[1].id)
            val claim = journal.claim(migrated[1].id, "owner", "new-process")!!
            val step = journal.beginStep(claim, "link")!!
            val linked = journal.finishStep(step, "link", UploadStepResult("Done", fileId = 42))!!
            journal.release(linked)
            journal.cancel("owner", setOf(migrated[1].id))
            db.close(); db = open(); journal = UploadJournal(db)
            val reopened = journal.jobs("owner")
            assertEquals(receipt, reopened[1].receipt)
            assertEquals("Done", reopened[1].linkStep)
            assertEquals("Suppressed", reopened[1].albumStep)
            assertEquals("Cancelled", reopened[2].state)
            assertEquals(EnqueueResult.Accepted("batch-Accepted", 1), journal.prior("owner", "request-Accepted"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

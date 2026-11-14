package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.core.network.capabilities.NextcloudCapabilitiesClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NextcloudTrashBinTest {
    @Test fun `only an answer from the server allows deleting cloud copies`() =
        runBlocking {
            var response = 200 to ""
            NextcloudFixture { response }.use { fixture ->
                val trashBin = NextcloudTrashBin(
                    NextcloudCapabilitiesClient(fixture.transport) { fixture.credentials },
                )

                suspend fun status(code: Int, body: String): CloudTrashStatus {
                    response = code to body
                    return trashBin.status()
                }
                assertEquals(
                    CloudTrashStatus.Available,
                    status(200, capabilities("""{"undelete":true}""")),
                )
                assertEquals(
                    CloudTrashStatus.Missing,
                    status(200, capabilities("""{"undelete":false}""")),
                )
                // With files_trashbin disabled the stand omits undelete instead of sending false.
                assertEquals(
                    CloudTrashStatus.Missing,
                    status(200, capabilities("""{"versioning":true}""")),
                )
                assertEquals(
                    CloudTrashStatus.Missing,
                    status(200, """{"ocs":{"data":{"capabilities":{}}}}"""),
                )
                listOf(
                    503 to capabilities("""{"undelete":true}"""),
                    200 to capabilities("""{"undelete":1}"""),
                    200 to "{}",
                    200 to "not JSON",
                ).forEach { (code, body) ->
                    assertEquals(CloudTrashStatus.Unreachable, status(code, body))
                }
            }
        }

    private fun capabilities(files: String) =
        """{"ocs":{"meta":{"statuscode":200},"data":{"capabilities":{"files":$files}}}}"""
}

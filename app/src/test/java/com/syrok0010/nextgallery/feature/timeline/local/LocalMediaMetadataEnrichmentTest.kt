package com.syrok0010.nextgallery.feature.timeline.local

import com.syrok0010.nextgallery.core.database.LocalMediaMetadataDao
import com.syrok0010.nextgallery.core.database.LocalMediaMetadataEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMediaMetadataEnrichmentTest {
    @Test
    fun `new scan reuses persisted EXIF and invalidates changed generation metadata and version`() = runBlocking {
        val cache = Cache()
        var opens = 0
        suspend fun scan(rows: List<LocalMediaMetadata>, version: String = "v1") {
            val enrichment = LocalMediaMetadataEnrichment(cache, mapOf("external_primary" to version)) {
                opens++
                it.copy(imageUniqueId = "exif", memoriesTimelineEpochSeconds = 42)
            }
            enrichment.load()
            rows.chunked(200).forEach { batch ->
                val result = enrichment.enrich(batch) { error("Unexpected fallback") }
                result.forEach { assertEquals("exif", it.imageUniqueId) }
            }
            enrichment.finish()
        }
        val rows = List(20_000) { row(it) }
        scan(rows)
        assertEquals(20_000, opens)
        scan(rows)
        assertEquals(20_000, opens)
        scan(rows.dropLast(1) + row(19_999).copy(generationModified = 2))
        assertEquals(20_001, opens)
        scan(rows.dropLast(1) + row(19_999).copy(displayName = "renamed"))
        assertEquals(20_002, opens)
        scan(rows, "v2")
        assertEquals(40_002, opens)
        scan(rows.take(1), "v2")
        assertEquals(1, cache.items.size)
    }

    @Test
    fun `failed EXIF is retried and interrupted scan does not evict cached metadata`() = runBlocking {
        val cache = Cache()
        var attempts = 0
        repeat(2) {
            val enrichment = LocalMediaMetadataEnrichment(cache, mapOf("external_primary" to "v1")) {
                attempts++
                null
            }
            enrichment.load()
            enrichment.enrich(listOf(row(1))) { it }
        }
        assertEquals(2, attempts)
        assertEquals(1, cache.items.size)
        val successful = LocalMediaMetadataEnrichment(cache, mapOf("external_primary" to "v1")) { it }
        successful.load()
        successful.enrich(listOf(row(2))) { it }
        val interrupted = LocalMediaMetadataEnrichment(cache, mapOf("external_primary" to "v1")) { it }
        interrupted.load()
        assertEquals(2, cache.items.size)
    }

    @Test
    fun `unmounted volume is retained until it can be reconciled`() = runBlocking {
        val cache = Cache()
        val first = LocalMediaMetadataEnrichment(cache, mapOf("external_primary" to "v1", "card" to "v1")) { it }
        first.load()
        first.enrich(listOf(row(1).copy(volumeName = "card"))) { it }
        first.finish()
        val next = LocalMediaMetadataEnrichment(cache, mapOf("external_primary" to "v1")) { it }
        next.load()
        next.finish()
        assertEquals(setOf(row(1).contentUri), next.unavailableUris)
        assertEquals(1, cache.items.size)
    }

    private class Cache : LocalMediaMetadataDao {
        val items = mutableMapOf<String, LocalMediaMetadataEntity>()
        override suspend fun load() = items.values.toList()
        override suspend fun upsert(items: List<LocalMediaMetadataEntity>) {
            items.forEach { this.items[it.contentUri] = it }
        }
        override suspend fun delete(uri: String) { items.remove(uri) }
    }

    private fun row(id: Int) = LocalMediaMetadata(
        contentUri = "content://media/external/images/media/$id",
        displayName = "$id.jpg", mimeType = "image/jpeg", width = 100, height = 100,
        sizeBytes = 100, dateTakenMillis = 1_000, dateModifiedSeconds = 1,
        dateAddedSeconds = 1, durationMillis = null, isVideo = false,
        generationModified = 1, volumeName = "external_primary",
    )
}

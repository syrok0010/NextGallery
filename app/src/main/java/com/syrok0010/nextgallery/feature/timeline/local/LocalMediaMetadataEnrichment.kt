package com.syrok0010.nextgallery.feature.timeline.local

import com.syrok0010.nextgallery.core.database.LocalMediaMetadataDao
import com.syrok0010.nextgallery.core.database.LocalMediaMetadataEntity
import java.util.TimeZone
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One scan owns the cache view; failed file reads are retried on the next scan. */
internal class LocalMediaMetadataEnrichment(
    private val cache: LocalMediaMetadataDao?,
    private val versions: Map<String, String>,
    private val readExif: suspend (LocalMediaMetadata) -> LocalMediaMetadata?,
) {
    private var cached = emptyMap<String, LocalMediaMetadataEntity>()
    private val seen = mutableSetOf<String>()
    var unavailableUris: Set<String> = emptySet()
        private set
    private val timeZone = TimeZone.getDefault().id

    suspend fun load() {
        cached = cache?.load().orEmpty().associateBy { it.contentUri }
        unavailableUris = cached.values.filter { entry ->
            val volume = Json.decodeFromString<LocalMediaMetadata>(entry.metadataJson).volumeName
            volume != null && volume !in versions
        }.mapTo(mutableSetOf()) { it.contentUri }
        seen.addAll(unavailableUris)
    }

    suspend fun enrich(
        batch: List<LocalMediaMetadata>,
        fallback: (LocalMediaMetadata) -> LocalMediaMetadata,
    ): List<LocalMediaMetadata> {
        val results = batch.chunked(8).flatMap { chunk ->
            coroutineScope {
                chunk.map { metadata ->
                    async {
                        enrichOne(metadata, fallback)
                    }
                }.awaitAll()
            }
        }
        val entries = results.mapNotNull { it.cacheUpdate }
        if (entries.isNotEmpty()) cache?.upsert(entries)
        seen.addAll(batch.map { it.contentUri })
        return results.map { it.metadata }
    }

    private suspend fun enrichOne(
        metadata: LocalMediaMetadata,
        fallback: (LocalMediaMetadata) -> LocalMediaMetadata,
    ): EnrichmentResult {
        val version = versions[metadata.volumeName]
        val fingerprint = Json.encodeToString(metadata) + "|" + version + "|" + timeZone
        val previous = cached[metadata.contentUri]
        val reusable = version != null && metadata.generationModified != null
        val unchanged = reusable && previous?.fingerprint == fingerprint && previous.exifComplete
        val enriched = if (unchanged) {
            Json.decodeFromString<LocalMediaMetadata>(checkNotNull(previous).metadataJson)
        } else {
            readExif(metadata)
        }
        val result = enriched ?: fallback(metadata)
        val entry = if (reusable && (previous?.fingerprint != fingerprint || !previous.exifComplete)) {
            LocalMediaMetadataEntity(
                metadata.contentUri, fingerprint, Json.encodeToString(result), enriched != null,
            )
        } else null
        return EnrichmentResult(result, entry)
    }

    suspend fun finish() {
        cached.keys.filterNot { it in seen }.forEach { cache?.delete(it) }
    }
}

private data class EnrichmentResult(
    val metadata: LocalMediaMetadata,
    val cacheUpdate: LocalMediaMetadataEntity?,
)

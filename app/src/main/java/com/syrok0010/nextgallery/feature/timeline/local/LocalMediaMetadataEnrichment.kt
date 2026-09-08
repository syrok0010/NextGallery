package com.syrok0010.nextgallery.data.local

import com.syrok0010.nextgallery.data.cache.LocalMediaMetadataDao
import com.syrok0010.nextgallery.data.cache.LocalMediaMetadataEntity
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
                        result to entry
                    }
                }.awaitAll()
            }
        }
        val entries = results.mapNotNull { it.second }
        if (entries.isNotEmpty()) cache?.upsert(entries)
        seen.addAll(batch.map { it.contentUri })
        return results.map { it.first }
    }

    suspend fun finish() {
        cached.keys.filterNot { it in seen }.forEach { cache?.delete(it) }
    }
}

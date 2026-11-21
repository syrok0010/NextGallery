package com.syrok0010.nextgallery.core.database

import androidx.room.withTransaction
import com.syrok0010.nextgallery.core.media.MediaAlias
import com.syrok0010.nextgallery.core.media.MediaAliasKind
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.MediaIdentityConflict
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaIdentityResolution
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.reconcileMediaIdentities

class RoomMediaIdentityRegistry(
    private val database: NextGalleryDatabase,
    private val mediaIdFactory: () -> MediaId = MediaId::generate,
) : MediaIdentityRegistry {
    private val dao = database.mediaIdentityDao()

    override suspend fun resolve(
        candidates: List<MediaIdentityCandidate>,
    ): MediaIdentityResolution {
        if (candidates.isEmpty()) return MediaIdentityResolution(emptyMap(), emptyList())
        return database.withTransaction {
            val rows = lookup(candidates)
            val observedAliases = candidates.associate { it.source to it.aliases }
            val requestedAliases = candidates.flatMapTo(mutableSetOf()) { it.aliases }
            val aliasesBySource = rows
                .flatMap { row ->
                    row.sources().map { it to row.aliases(it.source) }
                }.toMap()
                .toMutableMap()
            val sourceIds = rows
                .flatMap { row ->
                    row.sources().map {
                        it to MediaId(
                            row.mediaId,
                        )
                    }
                }.toMap()
            // Observations in this batch replace stale aliases only on the reporting side.
            val aliasIds = sourceIds
                .flatMap { (source, id) ->
                    aliasesBySource
                        .getValue(source)
                        .filter { alias ->
                            alias in requestedAliases &&
                                observedAliases[source]?.contains(
                                    alias,
                                ) != false
                        }.map { it to id }
                }.toMap()
            val reconciliation = reconcileMediaIdentities(
                candidates = candidates,
                initialSourceMediaIds = sourceIds,
                initialAliasMediaIds = aliasIds,
                initialLocalMediaIds = rows
                    .filter {
                        it.localUri != null
                    }.mapTo(mutableSetOf()) { MediaId(it.mediaId) },
                mediaIdFactory = mediaIdFactory,
                initialMediaSources = rows.associate {
                    MediaId(
                        it.mediaId,
                    ) to it.sources().toSet()
                },
            )
            val conflicts = reconciliation.resolution.conflicts.associateBy { it.source }
            candidates.forEach { candidate ->
                aliasesBySource[candidate.source] = candidate.aliases
                    .takeUnless {
                        candidate.source in conflicts
                    }.orEmpty()
                if (candidate.source !in conflicts) {
                    dao.deleteConflict(
                        candidate.source.source,
                        candidate.source.sourceKey,
                    )
                }
            }
            val updated = reconciliation.sourceMediaIds.entries
                .groupBy({
                    it.value
                }, { it.key })
                .map { (id, sources) ->
                    val local = sources.singleOrNull { it.source == MediaSourceKind.Local }
                    val remote = sources.singleOrNull { it.source == MediaSourceKind.Memories }
                    check(
                        sources.size == listOfNotNull(local, remote).size,
                    ) { "Multiple copies from one source" }
                    val localAliases = aliasesBySource[local].orEmpty()
                    val remoteAliases = aliasesBySource[remote].orEmpty()
                    MediaIdentityEntity(
                        mediaId = id.value,
                        localUri = local?.sourceKey,
                        fileId = remote?.sourceKey?.toLong(),
                        localAuid = localAliases.valueOf(MediaAliasKind.Auid),
                        localBuid = localAliases.valueOf(MediaAliasKind.Buid),
                        remoteAuid = remoteAliases.valueOf(MediaAliasKind.Auid),
                        remoteBuid = remoteAliases.valueOf(MediaAliasKind.Buid),
                    )
                }
            val previous = rows.associateBy { it.mediaId }
            val next = updated.associateBy { it.mediaId }
            // Remove the old standalone row before transferring its URI/fileId. All writes roll
            // back together if a UNIQUE constraint rejects the new composition.
            rows
                .filter {
                    it != next[it.mediaId]
                }.map { it.mediaId }
                .chunked(QUERY_CHUNK_SIZE)
                .forEach { dao.delete(it) }
            dao.insert(updated.filter { it != previous[it.mediaId] })
            if (conflicts.isNotEmpty()) dao.upsertConflicts(conflicts.values.map { it.toEntity() })
            reconciliation.resolution
        }
    }

    private suspend fun lookup(
        candidates: List<MediaIdentityCandidate>,
    ): List<MediaIdentityEntity> {
        val exact = candidates.groupBy { it.source.source }.flatMap { (kind, copies) ->
            copies
                .map { it.source.sourceKey }
                .distinct()
                .chunked(
                    QUERY_CHUNK_SIZE,
                ).flatMap { keys ->
                    when (kind) {
                        MediaSourceKind.Local -> dao.byLocalUris(keys)
                        MediaSourceKind.Memories -> dao.byFileIds(keys.map(String::toLong))
                    }
                }
        }
        val aliased = candidates
            .flatMapTo(mutableSetOf()) { it.aliases }
            .groupBy { it.kind }
            .flatMap { (kind, aliases) ->
                aliases.map { it.value }.chunked(QUERY_CHUNK_SIZE).flatMap { values ->
                    when (kind) {
                        MediaAliasKind.Auid -> dao.byAuid(values)
                        MediaAliasKind.Buid -> dao.byBuid(values)
                    }
                }
            }
        return (exact + aliased).distinctBy { it.mediaId }
    }

    override suspend fun forget(sources: Collection<MediaSourceIdentity>) {
        if (sources.isEmpty()) return
        database.withTransaction {
            sources.distinct().forEach { source ->
                val row = dao.find(source)
                if (row != null) {
                    val surviving = row.sources().singleOrNull { it != source }
                    when {
                        surviving == null -> dao.delete(listOf(row.mediaId))

                        source.source == MediaSourceKind.Local -> dao.update(
                            row.copy(localUri = null, localAuid = null, localBuid = null),
                        )

                        else -> dao.update(
                            row.copy(fileId = null, remoteAuid = null, remoteBuid = null),
                        )
                    }
                }
                dao.deleteConflict(source.source, source.sourceKey)
            }
        }
    }

    override suspend fun link(existing: MediaSourceIdentity, linked: MediaSourceIdentity): Boolean =
        database.withTransaction {
            val row = dao.find(existing) ?: return@withTransaction false
            val occupied = row.sources().singleOrNull { it.source == linked.source }
            if (occupied != null) {
                if (occupied != linked) return@withTransaction false
                dao.deleteConflict(linked.source, linked.sourceKey)
                return@withTransaction true
            }
            val previous = dao.find(linked)
            if (previous != null && previous.sources().size != 1) return@withTransaction false
            val aliases = previous?.aliases(linked.source).orEmpty()
            val joined = when (linked.source) {
                MediaSourceKind.Local -> row.copy(
                    localUri = linked.sourceKey,
                    localAuid = aliases.valueOf(MediaAliasKind.Auid),
                    localBuid = aliases.valueOf(MediaAliasKind.Buid),
                )

                MediaSourceKind.Memories -> row.copy(
                    fileId = linked.sourceKey.toLong(),
                    remoteAuid = aliases.valueOf(MediaAliasKind.Auid),
                    remoteBuid = aliases.valueOf(MediaAliasKind.Buid),
                )
            }
            if (previous != null) dao.delete(listOf(previous.mediaId))
            dao.update(joined)
            dao.deleteConflict(linked.source, linked.sourceKey)
            true
        }

    suspend fun conflicts(): List<MediaIdentityConflict> =
        dao.conflicts().map { entity ->
            MediaIdentityConflict(
                source = MediaSourceIdentity(entity.source, entity.sourceKey),
                aliases = buildSet {
                    entity.auid?.let { add(MediaAlias(MediaAliasKind.Auid, it)) }
                    entity.buid?.let { add(MediaAlias(MediaAliasKind.Buid, it)) }
                },
                conflictingMediaIds = entity.conflictingMediaIds
                    .split(
                        CONFLICT_SEPARATOR,
                    ).filter(String::isNotBlank)
                    .mapTo(mutableSetOf(), ::MediaId),
            )
        }

    private fun MediaIdentityConflict.toEntity() =
        MediaIdentityConflictEntity(
            source = source.source,
            sourceKey = source.sourceKey,
            auid = aliases.valueOf(MediaAliasKind.Auid),
            buid = aliases.valueOf(MediaAliasKind.Buid),
            conflictingMediaIds = conflictingMediaIds
                .map {
                    it.value
                }.sorted()
                .joinToString(CONFLICT_SEPARATOR),
        )

    private companion object {
        // An alias query binds every value twice, once for each side of the identity row.
        const val QUERY_CHUNK_SIZE = 400
        const val CONFLICT_SEPARATOR = "\n"
    }
}

internal fun MediaIdentityEntity.sources(): List<MediaSourceIdentity> =
    listOfNotNull(
        localUri?.let { MediaSourceIdentity(MediaSourceKind.Local, it) },
        fileId?.let { MediaSourceIdentity(MediaSourceKind.Memories, it.toString()) },
    )

internal fun MediaIdentityEntity.aliases(source: MediaSourceKind): Set<MediaAlias> =
    buildSet {
        val auid = if (source == MediaSourceKind.Local) localAuid else remoteAuid
        val buid = if (source == MediaSourceKind.Local) localBuid else remoteBuid
        auid?.let { add(MediaAlias(MediaAliasKind.Auid, it)) }
        buid?.let { add(MediaAlias(MediaAliasKind.Buid, it)) }
    }

private fun Set<MediaAlias>.valueOf(kind: MediaAliasKind): String? =
    firstOrNull {
        it.kind == kind
    }?.value

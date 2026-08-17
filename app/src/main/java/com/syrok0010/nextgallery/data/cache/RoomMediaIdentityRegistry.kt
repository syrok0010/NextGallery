package com.syrok0010.nextgallery.data.cache

import androidx.room.withTransaction
import com.syrok0010.nextgallery.data.memories.MediaAlias
import com.syrok0010.nextgallery.data.memories.MediaAliasKind
import com.syrok0010.nextgallery.data.memories.MediaIdentityCandidate
import com.syrok0010.nextgallery.data.memories.MediaIdentityConflict
import com.syrok0010.nextgallery.data.memories.MediaIdentityRegistry
import com.syrok0010.nextgallery.data.memories.MediaIdentityResolution
import com.syrok0010.nextgallery.data.memories.reconcileMediaIdentities
import com.syrok0010.nextgallery.domain.media.MediaId
import com.syrok0010.nextgallery.domain.media.MediaSourceIdentity
import com.syrok0010.nextgallery.domain.media.MediaSourceKind

class RoomMediaIdentityRegistry(
    private val database: TimelineCacheDatabase,
    private val mediaIdFactory: () -> MediaId = MediaId::generate,
) : MediaIdentityRegistry {
    private val dao = database.timelineCacheDao()

    override suspend fun resolve(candidates: List<MediaIdentityCandidate>): MediaIdentityResolution {
        if (candidates.isEmpty()) return MediaIdentityResolution(emptyMap(), emptyList())

        return database.withTransaction {
            val sourceMediaIds = candidates
                .groupBy { it.source.source }
                .flatMap { (source, sourceCandidates) ->
                    sourceCandidates.map { it.source.sourceKey }.distinct().chunked(QUERY_CHUNK_SIZE).flatMap { keys ->
                        dao.mediaIdentities(source, keys)
                    }
                }
                .associate { entity ->
                    MediaSourceIdentity(MediaSourceKind.valueOf(entity.source), entity.sourceKey) to MediaId(entity.mediaId)
                }
            val requestedAliases = candidates.flatMapTo(mutableSetOf()) { it.aliases }
            val aliasMediaIds = requestedAliases
                .map { it.value }
                .distinct()
                .chunked(QUERY_CHUNK_SIZE)
                .flatMap { values -> dao.mediaIdentityAliases(values) }
                .map { entity -> MediaAlias(MediaAliasKind.valueOf(entity.kind), entity.value) to MediaId(entity.mediaId) }
                .filter { (alias, _) -> alias in requestedAliases }
                .toMap()
            val localMediaIds = aliasMediaIds.values
                .map { it.value }
                .distinct()
                .chunked(QUERY_CHUNK_SIZE)
                .flatMap { ids -> dao.mediaIdentitiesForMediaIds(ids) }
                .filter { it.source == MediaSourceKind.Local.name }
                .mapTo(mutableSetOf()) { MediaId(it.mediaId) }
            val reconciliation = reconcileMediaIdentities(
                candidates = candidates,
                initialSourceMediaIds = sourceMediaIds,
                initialAliasMediaIds = aliasMediaIds,
                initialLocalMediaIds = localMediaIds,
                mediaIdFactory = mediaIdFactory,
            )

            reconciliation.reassignments.forEach { (from, to) ->
                dao.reassignMediaIdentities(from.value, to.value)
                dao.reassignMediaIdentityAliases(from.value, to.value)
            }
            dao.upsertMediaIdentities(
                reconciliation.resolution.mediaIds.map { (source, mediaId) ->
                    MediaIdentityEntity(source.source.name, source.sourceKey, mediaId.value)
                },
            )
            val conflictsBySource = reconciliation.resolution.conflicts.associateBy { it.source }
            val resolvedCandidates = candidates.filterNot { it.source in conflictsBySource }
            val existingConflictSources = dao.mediaIdentityConflicts().mapTo(mutableSetOf()) { entity ->
                MediaSourceIdentity(MediaSourceKind.valueOf(entity.source), entity.sourceKey)
            }
            resolvedCandidates
                .map { it.source }
                .filter { it in existingConflictSources }
                .forEach { source -> dao.deleteMediaIdentityConflict(source.source, source.sourceKey) }
            val aliasEntities = resolvedCandidates.flatMap { candidate ->
                val mediaId = reconciliation.resolution.mediaIds.getValue(candidate.source)
                candidate.aliases.map { alias ->
                    MediaIdentityAliasEntity(alias.kind.name, alias.value, mediaId.value)
                }
            }.distinctBy { entity -> entity.kind to entity.value }
            if (aliasEntities.isNotEmpty()) dao.upsertMediaIdentityAliases(aliasEntities)
            if (conflictsBySource.isNotEmpty()) {
                dao.upsertMediaIdentityConflicts(conflictsBySource.values.map { it.toEntity() })
            }
            reconciliation.resolution
        }
    }

    suspend fun conflicts(): List<MediaIdentityConflict> = dao.mediaIdentityConflicts().map { entity ->
        MediaIdentityConflict(
            source = MediaSourceIdentity(MediaSourceKind.valueOf(entity.source), entity.sourceKey),
            aliases = buildSet {
                entity.auid?.let { add(MediaAlias(MediaAliasKind.Auid, it)) }
                entity.buid?.let { add(MediaAlias(MediaAliasKind.Buid, it)) }
            },
            conflictingMediaIds = entity.conflictingMediaIds
                .split(CONFLICT_SEPARATOR)
                .filter(String::isNotBlank)
                .mapTo(mutableSetOf(), ::MediaId),
        )
    }

    private fun MediaIdentityConflict.toEntity() = MediaIdentityConflictEntity(
        source = source.source.name,
        sourceKey = source.sourceKey,
        auid = aliases.firstOrNull { it.kind == MediaAliasKind.Auid }?.value,
        buid = aliases.firstOrNull { it.kind == MediaAliasKind.Buid }?.value,
        conflictingMediaIds = conflictingMediaIds.map { it.value }.sorted().joinToString(CONFLICT_SEPARATOR),
    )

    private companion object {
        const val QUERY_CHUNK_SIZE = 500
        const val CONFLICT_SEPARATOR = "\n"
    }
}

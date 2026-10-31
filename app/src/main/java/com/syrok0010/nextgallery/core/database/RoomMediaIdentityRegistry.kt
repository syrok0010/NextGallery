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
            val known = lookupKnownIdentities(candidates)
            val reconciliation = reconcileMediaIdentities(
                candidates = candidates,
                initialSourceMediaIds = known.sources,
                initialAliasMediaIds = known.aliases,
                initialLocalMediaIds = known.localIds,
                mediaIdFactory = mediaIdFactory,
                initialMediaSources = known.mediaSources,
            )

            persistReconciliation(candidates, reconciliation)
            reconciliation.resolution
        }
    }

    private suspend fun lookupKnownIdentities(
        candidates: List<MediaIdentityCandidate>,
    ): KnownIdentities {
        val sourceMediaIds = candidates
            .groupBy { it.source.identifierKind() }
            .flatMap { (kind, sourceCandidates) ->
                sourceCandidates
                    .map { it.source.sourceKey }
                    .distinct()
                    .chunked(
                        QUERY_CHUNK_SIZE,
                    ).flatMap { keys ->
                        dao.identifiers(kind, keys)
                    }
            }.associate { entity -> entity.toSourceIdentity() to MediaId(entity.mediaId) }
        // A copy in this batch is matched by the aliases it reports now, not by its stored ones.
        val currentAliases = candidates.associate { it.source to it.aliases }
        val aliasMediaIds = candidates
            .flatMapTo(mutableSetOf()) { it.aliases }
            .groupBy { it.kind }
            .flatMap { (kind, aliases) ->
                aliases.map { it.value }.distinct().chunked(QUERY_CHUNK_SIZE).flatMap { values ->
                    dao.identifiersByAlias(kind, values).mapNotNull { entity ->
                        val alias = MediaAlias(kind, checkNotNull(entity.alias(kind)))
                        val current = currentAliases[entity.toSourceIdentity()]
                        val stale = current != null && alias !in current
                        (alias to MediaId(entity.mediaId)).takeUnless { stale }
                    }
                }
            }.toMap()
        val aliasedCopies = aliasMediaIds.values
            .map { it.value }
            .distinct()
            .chunked(QUERY_CHUNK_SIZE)
            .flatMap { mediaIds -> dao.identifiersForMediaIds(mediaIds) }
        val localMediaIds = aliasedCopies
            .filter { it.kind == MediaIdentifierKind.LocalContent }
            .mapTo(mutableSetOf()) { MediaId(it.mediaId) }
        val mediaSources = aliasedCopies
            .groupBy({ MediaId(it.mediaId) }, { it.toSourceIdentity() })
            .mapValues { (_, sources) -> sources.toSet() }
        return KnownIdentities(sourceMediaIds, aliasMediaIds, localMediaIds, mediaSources)
    }

    private suspend fun persistReconciliation(
        candidates: List<MediaIdentityCandidate>,
        reconciliation: com.syrok0010.nextgallery.core.media.MediaIdentityReconciliation,
    ) {
        reconciliation.reassignments.forEach { (from, to) ->
            dao.reassignMediaId(from.value, to.value)
        }
        val conflictsBySource = reconciliation.resolution.conflicts.associateBy { it.source }
        val resolvedCandidates = candidates.filterNot { it.source in conflictsBySource }
        val existingConflictSources = dao.conflicts().mapTo(mutableSetOf()) { entity ->
            MediaSourceIdentity(entity.source, entity.sourceKey)
        }
        resolvedCandidates
            .map { it.source }
            .filter { it in existingConflictSources }
            .forEach { source -> dao.deleteConflict(source.source, source.sourceKey) }
        // Conflicting aliases are not published for matching, so such a copy keeps none.
        val aliasesBySource = resolvedCandidates.associate { it.source to it.aliases }
        dao.upsertIdentifiers(
            reconciliation.resolution.mediaIds.map { (source, mediaId) ->
                val aliases = aliasesBySource[source].orEmpty()
                MediaIdentifierEntity(
                    kind = source.identifierKind(),
                    value = source.sourceKey,
                    mediaId = mediaId.value,
                    auid = aliases.valueOf(MediaAliasKind.Auid),
                    buid = aliases.valueOf(MediaAliasKind.Buid),
                )
            },
        )
        if (conflictsBySource.isNotEmpty()) {
            dao.upsertConflicts(conflictsBySource.values.map { it.toEntity() })
        }
    }

    private data class KnownIdentities(
        val sources: Map<MediaSourceIdentity, MediaId>,
        val aliases: Map<MediaAlias, MediaId>,
        val localIds: Set<MediaId>,
        val mediaSources: Map<MediaId, Set<MediaSourceIdentity>>,
    )

    override suspend fun removeSource(source: MediaSourceKind) {
        database.withTransaction {
            dao.deleteIdentifiers(source.identifierKind())
            dao.deleteConflicts(source)
        }
    }

    override suspend fun link(
        existing: MediaSourceIdentity,
        linked: MediaSourceIdentity,
        isCurrent: () -> Boolean,
    ): Boolean =
        database.withTransaction {
            if (!isCurrent()) return@withTransaction false
            val mediaId = dao
                .identifiers(existing.identifierKind(), listOf(existing.sourceKey))
                .singleOrNull()
                ?.mediaId
                ?: return@withTransaction false
            val linkedKind = linked.identifierKind()
            val previousEntity = dao
                .identifiers(
                    linkedKind,
                    listOf(linked.sourceKey),
                ).singleOrNull()
            val previous = previousEntity?.mediaId
            if (previous == mediaId) {
                dao.deleteConflict(linked.source, linked.sourceKey)
                return@withTransaction true
            }

            // A link is permanent for the lifetime of these source identities. Do not replace a
            // server copy that is already attached to the local object.
            val existingLinkedCopies = dao
                .identifiersForMediaIds(listOf(mediaId))
                .filter { it.kind == linkedKind }
            if (existingLinkedCopies.isNotEmpty()) {
                return@withTransaction false
            }

            if (previous != null && previous != mediaId) {
                val previousSources = dao.identifiersForMediaIds(listOf(previous))
                // An unlinked server object may have been indexed between PUT and this follow-up.
                // Merge that object, but never take a link away from another local object.
                if (previousSources.any { it.kind != linkedKind || it.value != linked.sourceKey }) {
                    return@withTransaction false
                }
                dao.reassignMediaId(previous, mediaId)
            }
            dao.deleteConflict(linked.source, linked.sourceKey)
            // The linked copy keeps the aliases it was indexed with.
            dao.upsertIdentifiers(
                listOf(
                    previousEntity?.copy(mediaId = mediaId)
                        ?: MediaIdentifierEntity(linkedKind, linked.sourceKey, mediaId),
                ),
            )
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
                    .split(CONFLICT_SEPARATOR)
                    .filter(String::isNotBlank)
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
        const val QUERY_CHUNK_SIZE = 500
        const val CONFLICT_SEPARATOR = "\n"
    }
}

private fun MediaSourceIdentity.identifierKind(): MediaIdentifierKind = source.identifierKind()

private fun MediaSourceKind.identifierKind(): MediaIdentifierKind =
    when (this) {
        MediaSourceKind.Memories -> MediaIdentifierKind.MemoriesFile
        MediaSourceKind.Local -> MediaIdentifierKind.LocalContent
    }

private suspend fun MediaIdentityDao.identifiersByAlias(
    kind: MediaAliasKind,
    values: Collection<String>,
): List<MediaIdentifierEntity> =
    when (kind) {
        MediaAliasKind.Auid -> identifiersByAuid(values)
        MediaAliasKind.Buid -> identifiersByBuid(values)
    }

private fun MediaIdentifierEntity.alias(kind: MediaAliasKind): String? =
    when (kind) {
        MediaAliasKind.Auid -> auid
        MediaAliasKind.Buid -> buid
    }

private fun Set<MediaAlias>.valueOf(kind: MediaAliasKind): String? =
    firstOrNull { it.kind == kind }?.value

private fun MediaIdentifierEntity.toSourceIdentity(): MediaSourceIdentity =
    MediaSourceIdentity(
        source = when (kind) {
            MediaIdentifierKind.MemoriesFile -> MediaSourceKind.Memories
            MediaIdentifierKind.LocalContent -> MediaSourceKind.Local
        },
        sourceKey = value,
    )

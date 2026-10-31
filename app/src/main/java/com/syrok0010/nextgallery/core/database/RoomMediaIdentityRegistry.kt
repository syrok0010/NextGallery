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
            removeObsoleteAliases(candidates)
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

    private suspend fun removeObsoleteAliases(candidates: List<MediaIdentityCandidate>) {
        val currentAliases = candidates.associate { candidate ->
            (candidate.source.identifierKind() to candidate.source.sourceKey) to candidate.aliases
        }
        candidates.groupBy { it.source.identifierKind() }.forEach { (kind, sourceCandidates) ->
            sourceCandidates
                .map { it.source.sourceKey }
                .distinct()
                .chunked(
                    QUERY_CHUNK_SIZE,
                ).forEach { keys ->
                    val obsolete = dao.aliasesForSources(kind, keys).filter { alias ->
                        alias.toAlias() !in currentAliases.getValue(kind to alias.sourceKey)
                    }
                    dao.deleteAliases(obsolete)
                }
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
        val requestedAliases = candidates.flatMapTo(mutableSetOf()) { it.aliases }
        val aliasMediaIds = requestedAliases
            .groupBy { it.identifierKind() }
            .flatMap { (kind, aliases) ->
                aliases.map { it.value }.distinct().chunked(QUERY_CHUNK_SIZE).flatMap { values ->
                    dao.aliases(kind, values)
                }
            }.associate { entity -> entity.toAlias() to MediaId(entity.mediaId) }
        val aliasedCopies = aliasMediaIds.values
            .map { it.value }
            .distinct()
            .chunked(QUERY_CHUNK_SIZE)
            .flatMap { mediaIds -> dao.identifiersForMediaIds(mediaIds) }
            .filter { it.kind in SOURCE_IDENTIFIER_KINDS }
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
        val sourceIdentifiers = reconciliation.resolution.mediaIds.map { (source, mediaId) ->
            MediaIdentifierEntity(source.identifierKind(), source.sourceKey, mediaId.value)
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
        val aliasIdentifiers = resolvedCandidates.flatMap { candidate ->
            candidate.aliases.map { alias ->
                MediaSourceAliasEntity(
                    candidate.source.identifierKind(),
                    candidate.source.sourceKey,
                    alias.identifierKind(),
                    alias.value,
                )
            }
        }
        dao.upsertIdentifiers(sourceIdentifiers)
        dao.upsertAliases(aliasIdentifiers)
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
            dao.deleteAliases(source.identifierKind())
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
            val previous = dao
                .identifiers(
                    linkedKind,
                    listOf(linked.sourceKey),
                ).singleOrNull()
                ?.mediaId
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
                val previousSources = dao
                    .identifiersForMediaIds(listOf(previous))
                    .filter { it.kind in SOURCE_IDENTIFIER_KINDS }
                // An unlinked server object may have been indexed between PUT and this follow-up.
                // Merge that object, but never take a link away from another local object.
                if (previousSources.any { it.kind != linkedKind || it.value != linked.sourceKey }) {
                    return@withTransaction false
                }
                dao.reassignMediaId(previous, mediaId)
            }
            dao.deleteConflict(linked.source, linked.sourceKey)
            dao.upsertIdentifiers(
                listOf(MediaIdentifierEntity(linkedKind, linked.sourceKey, mediaId)),
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
            auid = aliases.firstOrNull { it.kind == MediaAliasKind.Auid }?.value,
            buid = aliases.firstOrNull { it.kind == MediaAliasKind.Buid }?.value,
            conflictingMediaIds = conflictingMediaIds
                .map {
                    it.value
                }.sorted()
                .joinToString(CONFLICT_SEPARATOR),
        )

    private companion object {
        const val QUERY_CHUNK_SIZE = 500
        const val CONFLICT_SEPARATOR = "\n"
        val SOURCE_IDENTIFIER_KINDS = listOf(
            MediaIdentifierKind.MemoriesFile,
            MediaIdentifierKind.LocalContent,
        )
    }
}

private fun MediaSourceIdentity.identifierKind(): MediaIdentifierKind = source.identifierKind()

private fun MediaSourceKind.identifierKind(): MediaIdentifierKind =
    when (this) {
        MediaSourceKind.Memories -> MediaIdentifierKind.MemoriesFile
        MediaSourceKind.Local -> MediaIdentifierKind.LocalContent
    }

private fun MediaAlias.identifierKind(): MediaIdentifierKind =
    when (kind) {
        MediaAliasKind.Auid -> MediaIdentifierKind.Auid
        MediaAliasKind.Buid -> MediaIdentifierKind.Buid
    }

private fun MediaIdentifierEntity.toSourceIdentity(): MediaSourceIdentity =
    MediaSourceIdentity(
        source = when (kind) {
            MediaIdentifierKind.MemoriesFile -> MediaSourceKind.Memories

            MediaIdentifierKind.LocalContent -> MediaSourceKind.Local

            MediaIdentifierKind.Auid,
            MediaIdentifierKind.Buid,
            -> error("Alias identifier cannot be converted to a source identity")
        },
        sourceKey = value,
    )

private fun MediaIdentifierEntity.toAlias(): MediaAlias =
    MediaAlias(
        kind = when (kind) {
            MediaIdentifierKind.Auid -> MediaAliasKind.Auid

            MediaIdentifierKind.Buid -> MediaAliasKind.Buid

            MediaIdentifierKind.MemoriesFile,
            MediaIdentifierKind.LocalContent,
            -> error("Source identifier cannot be converted to an alias")
        },
        value = value,
    )

private fun MediaSourceAliasEntity.toAlias(): MediaAlias =
    MediaAlias(
        kind = when (kind) {
            MediaIdentifierKind.Auid -> MediaAliasKind.Auid
            MediaIdentifierKind.Buid -> MediaAliasKind.Buid
            else -> error("Source identifier cannot be converted to an alias")
        },
        value = value,
    )

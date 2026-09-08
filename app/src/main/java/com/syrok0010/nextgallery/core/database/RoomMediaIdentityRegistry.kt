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
    private val database: NextGalleryDatabase,
    private val mediaIdFactory: () -> MediaId = MediaId::generate,
) : MediaIdentityRegistry {
    private val dao = database.mediaIdentityDao()

    override suspend fun resolve(candidates: List<MediaIdentityCandidate>): MediaIdentityResolution {
        if (candidates.isEmpty()) return MediaIdentityResolution(emptyMap(), emptyList())

        return database.withTransaction {
            val sourceMediaIds = candidates
                .groupBy { it.source.identifierKind() }
                .flatMap { (kind, sourceCandidates) ->
                    sourceCandidates.map { it.source.sourceKey }.distinct().chunked(QUERY_CHUNK_SIZE).flatMap { keys ->
                        dao.identifiers(kind, keys)
                    }
                }
                .associate { entity -> entity.toSourceIdentity() to MediaId(entity.mediaId) }
            val requestedAliases = candidates.flatMapTo(mutableSetOf()) { it.aliases }
            val aliasMediaIds = requestedAliases
                .groupBy { it.identifierKind() }
                .flatMap { (kind, aliases) ->
                    aliases.map { it.value }.distinct().chunked(QUERY_CHUNK_SIZE).flatMap { values ->
                        dao.identifiers(kind, values)
                    }
                }
                .associate { entity -> entity.toAlias() to MediaId(entity.mediaId) }
            val localMediaIds = aliasMediaIds.values
                .map { it.value }
                .distinct()
                .chunked(QUERY_CHUNK_SIZE)
                .flatMap { mediaIds -> dao.identifiersForMediaIds(mediaIds) }
                .filter { it.kind == MediaIdentifierKind.LocalContent }
                .mapTo(mutableSetOf()) { MediaId(it.mediaId) }
            val reconciliation = reconcileMediaIdentities(
                candidates = candidates,
                initialSourceMediaIds = sourceMediaIds,
                initialAliasMediaIds = aliasMediaIds,
                initialLocalMediaIds = localMediaIds,
                mediaIdFactory = mediaIdFactory,
            )

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
                val mediaId = reconciliation.resolution.mediaIds.getValue(candidate.source)
                candidate.aliases.map { alias ->
                    MediaIdentifierEntity(alias.identifierKind(), alias.value, mediaId.value)
                }
            }
            dao.upsertIdentifiers(
                (sourceIdentifiers + aliasIdentifiers).distinctBy { entity -> entity.kind to entity.value },
            )
            if (conflictsBySource.isNotEmpty()) {
                dao.upsertConflicts(conflictsBySource.values.map { it.toEntity() })
            }
            reconciliation.resolution
        }
    }

    override suspend fun removeSource(source: MediaSourceKind) {
        database.withTransaction {
            dao.deleteIdentifiers(source.identifierKind())
            dao.deleteConflicts(source)
            dao.deleteAliasesWithoutSource(
                aliasKinds = ALIAS_IDENTIFIER_KINDS,
                sourceKinds = SOURCE_IDENTIFIER_KINDS,
            )
        }
    }

    suspend fun conflicts(): List<MediaIdentityConflict> = dao.conflicts().map { entity ->
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

    private fun MediaIdentityConflict.toEntity() = MediaIdentityConflictEntity(
        source = source.source,
        sourceKey = source.sourceKey,
        auid = aliases.firstOrNull { it.kind == MediaAliasKind.Auid }?.value,
        buid = aliases.firstOrNull { it.kind == MediaAliasKind.Buid }?.value,
        conflictingMediaIds = conflictingMediaIds.map { it.value }.sorted().joinToString(CONFLICT_SEPARATOR),
    )

    private companion object {
        const val QUERY_CHUNK_SIZE = 500
        const val CONFLICT_SEPARATOR = "\n"
        val SOURCE_IDENTIFIER_KINDS = listOf(
            MediaIdentifierKind.MemoriesFile,
            MediaIdentifierKind.LocalContent,
        )
        val ALIAS_IDENTIFIER_KINDS = listOf(
            MediaIdentifierKind.Auid,
            MediaIdentifierKind.Buid,
        )
    }
}

private fun MediaSourceIdentity.identifierKind(): MediaIdentifierKind = source.identifierKind()

private fun MediaSourceKind.identifierKind(): MediaIdentifierKind = when (this) {
    MediaSourceKind.Memories -> MediaIdentifierKind.MemoriesFile
    MediaSourceKind.Local -> MediaIdentifierKind.LocalContent
}

private fun MediaAlias.identifierKind(): MediaIdentifierKind = when (kind) {
    MediaAliasKind.Auid -> MediaIdentifierKind.Auid
    MediaAliasKind.Buid -> MediaIdentifierKind.Buid
}

private fun MediaIdentifierEntity.toSourceIdentity(): MediaSourceIdentity = MediaSourceIdentity(
    source = when (kind) {
        MediaIdentifierKind.MemoriesFile -> MediaSourceKind.Memories
        MediaIdentifierKind.LocalContent -> MediaSourceKind.Local
        MediaIdentifierKind.Auid,
        MediaIdentifierKind.Buid,
        -> error("Alias identifier cannot be converted to a source identity")
    },
    sourceKey = value,
)

private fun MediaIdentifierEntity.toAlias(): MediaAlias = MediaAlias(
    kind = when (kind) {
        MediaIdentifierKind.Auid -> MediaAliasKind.Auid
        MediaIdentifierKind.Buid -> MediaAliasKind.Buid
        MediaIdentifierKind.MemoriesFile,
        MediaIdentifierKind.LocalContent,
        -> error("Source identifier cannot be converted to an alias")
    },
    value = value,
)

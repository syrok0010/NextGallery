package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.domain.media.MediaId
import com.syrok0010.nextgallery.domain.media.MediaSourceIdentity
import com.syrok0010.nextgallery.domain.media.MediaSourceKind

enum class MediaAliasKind {
    Auid,
    Buid,
}

data class MediaAlias(
    val kind: MediaAliasKind,
    val value: String,
)

data class MediaIdentityConflict(
    val source: MediaSourceIdentity,
    val aliases: Set<MediaAlias>,
    val conflictingMediaIds: Set<MediaId>,
)

data class MediaIdentityCandidate(
    val source: MediaSourceIdentity,
    val publishedMediaId: MediaId? = null,
    val aliases: Set<MediaAlias>,
)

internal fun mediaIdentityCandidate(
    source: MediaSourceIdentity,
    publishedMediaId: MediaId? = null,
    auid: String?,
    buid: String?,
) = MediaIdentityCandidate(
    source = source,
    publishedMediaId = publishedMediaId,
    aliases = buildSet {
        auid?.takeIf(String::isNotBlank)?.let { add(MediaAlias(MediaAliasKind.Auid, it)) }
        buid?.takeIf(String::isNotBlank)?.let { add(MediaAlias(MediaAliasKind.Buid, it)) }
    },
)

data class MediaIdentityResolution(
    val mediaIds: Map<MediaSourceIdentity, MediaId>,
    val conflicts: List<MediaIdentityConflict>,
)

interface MediaIdentityRegistry {
    suspend fun resolve(candidates: List<MediaIdentityCandidate>): MediaIdentityResolution
    suspend fun removeSource(source: MediaSourceKind)
}

internal data class MediaIdentityReconciliation(
    val resolution: MediaIdentityResolution,
    val sourceMediaIds: Map<MediaSourceIdentity, MediaId>,
    val aliasMediaIds: Map<MediaAlias, MediaId>,
    val reassignments: List<Pair<MediaId, MediaId>>,
)

internal fun reconcileMediaIdentities(
    candidates: List<MediaIdentityCandidate>,
    initialSourceMediaIds: Map<MediaSourceIdentity, MediaId>,
    initialAliasMediaIds: Map<MediaAlias, MediaId>,
    initialLocalMediaIds: Set<MediaId>,
    mediaIdFactory: () -> MediaId,
): MediaIdentityReconciliation {
    val sourceMediaIds = initialSourceMediaIds.toMutableMap()
    val aliasMediaIds = initialAliasMediaIds.toMutableMap()
    val localMediaIds = initialLocalMediaIds.toMutableSet()
    val conflicts = mutableListOf<MediaIdentityConflict>()
    val resolved = mutableMapOf<MediaSourceIdentity, MediaId>()
    val reassignments = mutableListOf<Pair<MediaId, MediaId>>()

    candidates.sortedBy { it.source.source != MediaSourceKind.Local }.forEach { candidate ->
        val publishedMediaId = sourceMediaIds[candidate.source]
            ?: candidate.publishedMediaId
            ?: mediaIdFactory()
        val matchedMediaIds = candidate.aliases.mapNotNull(aliasMediaIds::get).toSet()
        if (matchedMediaIds.size > 1) {
            conflicts += MediaIdentityConflict(
                source = candidate.source,
                aliases = candidate.aliases,
                conflictingMediaIds = matchedMediaIds,
            )
            val separateMediaId = if (publishedMediaId in matchedMediaIds) mediaIdFactory() else publishedMediaId
            sourceMediaIds[candidate.source] = separateMediaId
            resolved[candidate.source] = separateMediaId
            return@forEach
        }

        val matchedMediaId = matchedMediaIds.singleOrNull()
        val mediaId = if (
            candidate.source.source == MediaSourceKind.Local &&
            matchedMediaId != null &&
            matchedMediaId !in localMediaIds
        ) {
            sourceMediaIds.replaceAll { _, value -> if (value == matchedMediaId) publishedMediaId else value }
            aliasMediaIds.replaceAll { _, value -> if (value == matchedMediaId) publishedMediaId else value }
            reassignments += matchedMediaId to publishedMediaId
            publishedMediaId
        } else {
            matchedMediaId ?: publishedMediaId
        }
        sourceMediaIds[candidate.source] = mediaId
        candidate.aliases.forEach { alias -> aliasMediaIds[alias] = mediaId }
        if (candidate.source.source == MediaSourceKind.Local) localMediaIds += mediaId
        resolved[candidate.source] = mediaId
    }

    return MediaIdentityReconciliation(
        resolution = MediaIdentityResolution(resolved, conflicts),
        sourceMediaIds = sourceMediaIds,
        aliasMediaIds = aliasMediaIds,
        reassignments = reassignments,
    )
}

package com.syrok0010.nextgallery.core.media

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

fun mediaIdentityCandidate(
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

    /**
     * Records [linked] as the copy of the media already known as [existing] in [linked]'s source,
     * for example the server file just uploaded from a phone file. This exact link outranks
     * aliases: an object [linked] had alone is merged, and an earlier copy from the same source
     * becomes a separate object again. [isCurrent] is checked in the same transaction, so a link
     * cannot outlive a concurrent [removeSource]. Returns false when nothing was linked.
     */
    suspend fun link(
        existing: MediaSourceIdentity,
        linked: MediaSourceIdentity,
        isCurrent: () -> Boolean = { true },
    ): Boolean
}

data class MediaIdentityReconciliation(
    val resolution: MediaIdentityResolution,
    val sourceMediaIds: Map<MediaSourceIdentity, MediaId>,
    val aliasMediaIds: Map<MediaAlias, MediaId>,
    val reassignments: List<Pair<MediaId, MediaId>>,
)

/**
 * An alias joins a copy to an object only while that object has no other copy from the same
 * non-local source: two server files with one AUID stay two objects and are reported as a
 * conflict. [initialMediaSources] lists known copies of objects the aliases may point to.
 */
fun reconcileMediaIdentities(
    candidates: List<MediaIdentityCandidate>,
    initialSourceMediaIds: Map<MediaSourceIdentity, MediaId>,
    initialAliasMediaIds: Map<MediaAlias, MediaId>,
    initialLocalMediaIds: Set<MediaId>,
    mediaIdFactory: () -> MediaId,
    initialMediaSources: Map<MediaId, Set<MediaSourceIdentity>> = emptyMap(),
): MediaIdentityReconciliation {
    val sourceMediaIds = initialSourceMediaIds.toMutableMap()
    val aliasMediaIds = initialAliasMediaIds.toMutableMap()
    val localMediaIds = initialLocalMediaIds.toMutableSet()
    val mediaSources = mutableMapOf<MediaId, MutableSet<MediaSourceIdentity>>()
    initialMediaSources.forEach { (mediaId, sources) -> mediaSources.getOrPut(mediaId, ::mutableSetOf) += sources }
    initialSourceMediaIds.forEach { (source, mediaId) -> mediaSources.getOrPut(mediaId, ::mutableSetOf) += source }
    fun assign(source: MediaSourceIdentity, mediaId: MediaId) {
        sourceMediaIds[source]?.let { previous -> mediaSources[previous]?.remove(source) }
        sourceMediaIds[source] = mediaId
        mediaSources.getOrPut(mediaId, ::mutableSetOf) += source
    }
    val conflicts = mutableListOf<MediaIdentityConflict>()
    val resolved = mutableMapOf<MediaSourceIdentity, MediaId>()
    val reassignments = mutableListOf<Pair<MediaId, MediaId>>()

    fun reconcile(candidate: MediaIdentityCandidate) {
        val knownSourceMediaId = sourceMediaIds[candidate.source]
        val publishedMediaId = knownSourceMediaId
            ?: candidate.publishedMediaId
            ?: mediaIdFactory()
        val matchedMediaIds = candidate.aliases.mapNotNull(aliasMediaIds::get).toSet()

        // A persisted source identity is an exact link. A stale or colliding alias
        // must never move that source to another MediaId.
        if (knownSourceMediaId != null) {
            val conflictingMediaIds = matchedMediaIds - knownSourceMediaId
            if (conflictingMediaIds.isNotEmpty()) {
                conflicts += MediaIdentityConflict(
                    source = candidate.source,
                    aliases = candidate.aliases,
                    conflictingMediaIds = conflictingMediaIds + knownSourceMediaId,
                )
                assign(candidate.source, knownSourceMediaId)
                resolved[candidate.source] = knownSourceMediaId
                return
            }
            assign(candidate.source, knownSourceMediaId)
            candidate.aliases.forEach { alias -> aliasMediaIds[alias] = knownSourceMediaId }
            if (candidate.source.source == MediaSourceKind.Local) localMediaIds += knownSourceMediaId
            resolved[candidate.source] = knownSourceMediaId
            return
        }

        val occupied = candidate.source.source != MediaSourceKind.Local &&
            matchedMediaIds.any { mediaId ->
                mediaId != publishedMediaId &&
                    mediaSources[mediaId].orEmpty().any {
                        it != candidate.source && it.source == candidate.source.source
                    }
            }
        if (matchedMediaIds.size > 1 || occupied) {
            conflicts += MediaIdentityConflict(
                source = candidate.source,
                aliases = candidate.aliases,
                conflictingMediaIds = matchedMediaIds,
            )
            val separateMediaId = if (publishedMediaId in matchedMediaIds) mediaIdFactory() else publishedMediaId
            assign(candidate.source, separateMediaId)
            resolved[candidate.source] = separateMediaId
            return
        }

        val matchedMediaId = matchedMediaIds.singleOrNull()
        val mediaId = if (
            candidate.source.source == MediaSourceKind.Local &&
            matchedMediaId != null &&
            matchedMediaId !in localMediaIds
        ) {
            sourceMediaIds.replaceAll { _, value -> if (value == matchedMediaId) publishedMediaId else value }
            mediaSources.remove(matchedMediaId)?.let { mediaSources.getOrPut(publishedMediaId, ::mutableSetOf) += it }
            aliasMediaIds.replaceAll { _, value -> if (value == matchedMediaId) publishedMediaId else value }
            reassignments += matchedMediaId to publishedMediaId
            publishedMediaId
        } else {
            matchedMediaId ?: publishedMediaId
        }
        assign(candidate.source, mediaId)
        candidate.aliases.forEach { alias -> aliasMediaIds[alias] = mediaId }
        if (candidate.source.source == MediaSourceKind.Local) localMediaIds += mediaId
        resolved[candidate.source] = mediaId
    }

    // Copies already joined to an object keep it before other copies may claim it by alias.
    val (localCandidates, otherCandidates) = candidates.partition { it.source.source == MediaSourceKind.Local }
    localCandidates.forEach(::reconcile)
    val (joined, unjoined) = otherCandidates.partition { candidate ->
        (sourceMediaIds[candidate.source] ?: candidate.publishedMediaId) in
            candidate.aliases.mapNotNull(aliasMediaIds::get)
    }
    (joined + unjoined).forEach(::reconcile)

    return MediaIdentityReconciliation(
        resolution = MediaIdentityResolution(resolved, conflicts),
        sourceMediaIds = sourceMediaIds,
        aliasMediaIds = aliasMediaIds,
        reassignments = reassignments,
    )
}

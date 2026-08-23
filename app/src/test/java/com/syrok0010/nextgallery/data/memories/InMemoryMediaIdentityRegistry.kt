package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.domain.media.MediaId
import com.syrok0010.nextgallery.domain.media.MediaSourceIdentity
import com.syrok0010.nextgallery.domain.media.MediaSourceKind

class InMemoryMediaIdentityRegistry(
    private val mediaIdFactory: () -> MediaId = MediaId::generate,
) : MediaIdentityRegistry {
    private val sourceMediaIds = mutableMapOf<MediaSourceIdentity, MediaId>()
    private val aliasMediaIds = mutableMapOf<MediaAlias, MediaId>()

    override suspend fun resolve(candidates: List<MediaIdentityCandidate>): MediaIdentityResolution {
        val reconciliation = reconcileMediaIdentities(
            candidates = candidates,
            initialSourceMediaIds = sourceMediaIds,
            initialAliasMediaIds = aliasMediaIds,
            initialLocalMediaIds = sourceMediaIds
                .filterKeys { it.source == MediaSourceKind.Local }
                .values
                .toSet(),
            mediaIdFactory = mediaIdFactory,
        )
        sourceMediaIds.clear()
        sourceMediaIds.putAll(reconciliation.sourceMediaIds)
        aliasMediaIds.clear()
        aliasMediaIds.putAll(reconciliation.aliasMediaIds)
        return reconciliation.resolution
    }

    override suspend fun removeSource(source: MediaSourceKind) {
        val removedMediaIds = sourceMediaIds
            .filterKeys { it.source == source }
            .values
            .toSet()
        sourceMediaIds.keys.removeAll { it.source == source }
        val retainedMediaIds = sourceMediaIds.values.toSet()
        aliasMediaIds.entries.removeAll { (_, mediaId) ->
            mediaId in removedMediaIds && mediaId !in retainedMediaIds
        }
    }
}

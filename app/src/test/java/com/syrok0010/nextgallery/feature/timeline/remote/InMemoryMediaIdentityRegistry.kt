package com.syrok0010.nextgallery.feature.timeline.remote

import com.syrok0010.nextgallery.core.media.MediaAlias
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaIdentityResolution
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.reconcileMediaIdentities

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

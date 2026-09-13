package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.core.media.MediaId

/** Connection to the current playback session, shared by the strip's video items. */
internal interface FilmstripPlayback {
    fun sourceFor(id: MediaId): String?
    fun seek(id: MediaId, position: Long, finished: Boolean)
    fun finish(id: MediaId)

    data object None : FilmstripPlayback {
        override fun sourceFor(id: MediaId): String? = null
        override fun seek(id: MediaId, position: Long, finished: Boolean) = Unit
        override fun finish(id: MediaId) = Unit
    }
}


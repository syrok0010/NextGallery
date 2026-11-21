package com.syrok0010.nextgallery.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.router.NextGalleryRoute

internal enum class TopLevelDestination(
    val route: NextGalleryRoute,
    @get:StringRes val titleRes: Int,
    @get:DrawableRes val iconRes: Int,
) {
    Photos(NextGalleryRoute.Photos, R.string.library_photos, R.drawable.ic_photos),
    Albums(NextGalleryRoute.Albums, R.string.library_albums, R.drawable.ic_albums),
    ;

    val position: Int
        get() = entries.indexOf(this)
}

package com.syrok0010.nextgallery.app.ui

import androidx.navigation3.runtime.NavEntry
import com.syrok0010.nextgallery.feature.albums.AlbumLocation
import org.junit.Assert.*
import org.junit.Test

class NavigationEntryTest {
    @Test fun `Koin entry adapter preserves app identity and metadata for different albums`() {
        val first = NextGalleryRoute.Album(AlbumLocation.Remote("first"), "Title")
        val renamed = first.copy(title = "Renamed")
        val second = NextGalleryRoute.Album(AlbumLocation.Remote("second"), "Title")
        fun entry(route: NextGalleryRoute) =
            stableNavigationEntry(
                route,
                NavEntry(route, metadata = mapOf("transition" to "slide")) {},
            )
        assertEquals(first.navContentKey(), entry(first).contentKey)
        assertEquals(entry(first).contentKey, entry(renamed).contentKey)
        assertNotEquals(entry(first).contentKey, entry(second).contentKey)
        assertEquals(mapOf("transition" to "slide"), entry(first).metadata)
    }
}

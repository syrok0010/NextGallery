package com.syrok0010.nextgallery.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.navigation3.runtime.NavKey
import com.syrok0010.nextgallery.feature.albums.AlbumLocation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextGalleryAppRouteTest {
    @Test
    fun `restored albums stay selected within authenticated stack`() {
        val stack = listOf(NextGalleryRoute.Photos, NextGalleryRoute.Albums)
        assertEquals(stack, normalizedBackStack(stack))
    }

    @Test
    fun `empty, unknown, or duplicated destinations fall back to photos`() {
        assertEquals(listOf(NextGalleryRoute.Photos), normalizedBackStack(emptyList()))
        assertEquals(
            listOf(NextGalleryRoute.Photos),
            normalizedBackStack(listOf(NextGalleryRoute.Photos, object : NavKey {})),
        )
        assertEquals(
            listOf(NextGalleryRoute.Photos),
            normalizedBackStack(
                listOf(NextGalleryRoute.Photos, NextGalleryRoute.Albums, NextGalleryRoute.Albums),
            ),
        )
    }

    @Test
    fun `albums can be restored as the only authenticated destination`() {
        assertEquals(
            listOf(NextGalleryRoute.Albums),
            normalizedBackStack(listOf(NextGalleryRoute.Albums)),
        )
    }

    @Test
    fun `selecting a destination appends it without forcing photos first`() {
        assertEquals(
            listOf(NextGalleryRoute.Albums, NextGalleryRoute.Photos),
            destinationBackStack(listOf(NextGalleryRoute.Albums), NextGalleryRoute.Photos),
        )
    }

    @Test
    fun `selecting an existing destination makes it the top of the stack`() {
        assertEquals(
            listOf(NextGalleryRoute.Photos),
            destinationBackStack(
                listOf(NextGalleryRoute.Photos, NextGalleryRoute.Albums),
                NextGalleryRoute.Photos,
            ),
        )
    }

    @Test
    fun `photos to albums slides left and albums to photos slides right`() {
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.Left,
            transitionDirection(NextGalleryRoute.Photos, NextGalleryRoute.Albums),
        )
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.Right,
            transitionDirection(NextGalleryRoute.Albums, NextGalleryRoute.Photos),
        )
        assertNull(transitionDirection(NextGalleryRoute.Photos, NextGalleryRoute.Photos))
        assertNull(
            transitionDirection(
                NextGalleryRoute.Albums,
                NextGalleryRoute.Album(AlbumLocation.Folder("v", "Pictures/"), "Pictures"),
            ),
        )
    }

    @Test
    fun `owned nav content keys drive tab slide direction`() {
        assertEquals(
            NextGalleryRoute.Photos,
            topLevelRouteFromContentKey(NextGalleryRoute.Photos.navContentKey()),
        )
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.Left,
            transitionDirection(
                topLevelRouteFromContentKey(NextGalleryRoute.Photos.navContentKey()),
                topLevelRouteFromContentKey(NextGalleryRoute.Albums.navContentKey()),
            ),
        )
        assertNull(topLevelRouteFromContentKey("Photos:class something"))
        assertNull(
            topLevelRouteFromContentKey(
                NextGalleryRoute
                    .Album(AlbumLocation.Folder("v", "Pictures/"), "Pictures")
                    .navContentKey(),
            ),
        )
    }

    @Test
    fun `album destination survives serialization and normalization`() {
        val album = NextGalleryRoute.Album(
            AlbumLocation.Folder("external_primary", "Pictures/Отпуск/"),
            "Отпуск",
        )
        val restored = Json.decodeFromString<NextGalleryRoute>(
            Json.encodeToString<NextGalleryRoute>(album),
        )
        val stack =
            destinationBackStack(listOf(NextGalleryRoute.Photos, NextGalleryRoute.Albums), restored)
        assertEquals(listOf(NextGalleryRoute.Photos, NextGalleryRoute.Albums, album), stack)
        assertEquals(stack, normalizedBackStack(stack))
    }
}

package com.syrok0010.nextgallery.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.albums.AlbumLocation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextGalleryAppRouteTest {
    @Test
    fun `signed out session maps to login route`() {
        assertEquals(
            NextGalleryRoute.Login,
            SessionUiState.SignedOut.rootRoute(),
        )
    }

    @Test
    fun `signed in session maps to authenticated route`() {
        assertEquals(
            NextGalleryRoute.Photos,
            SessionUiState.SignedIn(credentials = credentials()).rootRoute(),
        )
    }

    @Test
    fun `signed out session resets authenticated back stack to login`() {
        val backStack = listOf(NextGalleryRoute.Photos)

        assertEquals(
            listOf(NextGalleryRoute.Login),
            syncedBackStack(backStack, SessionUiState.SignedOut),
        )
    }

    @Test
    fun `signed in session resets login back stack to authenticated`() {
        val backStack = listOf(NextGalleryRoute.Login)

        assertEquals(
            listOf(NextGalleryRoute.Photos),
            syncedBackStack(backStack, SessionUiState.SignedIn(credentials = credentials())),
        )
    }

    @Test
    fun `matching root route keeps existing back stack`() {
        val backStack = listOf(NextGalleryRoute.Photos)

        assertEquals(
            backStack,
            syncedBackStack(backStack, SessionUiState.SignedIn(credentials = credentials())),
        )
    }

    @Test
    fun `restored albums stay selected within signed in stack`() {
        val stack = listOf(NextGalleryRoute.Photos, NextGalleryRoute.Albums)
        assertEquals(stack, syncedBackStack(stack, SessionUiState.SignedIn(credentials())))
    }

    @Test
    fun `logout from albums discards both library destinations`() {
        assertEquals(
            listOf(NextGalleryRoute.Login),
            syncedBackStack(
                listOf(NextGalleryRoute.Photos, NextGalleryRoute.Albums),
                SessionUiState.SignedOut,
            ),
        )
    }

    @Test
    fun `login and duplicated destinations are discarded on restoration`() {
        val session = SessionUiState.SignedIn(credentials())
        assertEquals(
            listOf(NextGalleryRoute.Photos),
            syncedBackStack(listOf(NextGalleryRoute.Photos, NextGalleryRoute.Login), session),
        )
        assertEquals(
            listOf(NextGalleryRoute.Photos),
            syncedBackStack(
                listOf(NextGalleryRoute.Photos, NextGalleryRoute.Albums, NextGalleryRoute.Albums),
                session,
            ),
        )
    }

    @Test
    fun `albums can be restored as the only authenticated destination`() {
        val session = SessionUiState.SignedIn(credentials())
        assertEquals(
            listOf(NextGalleryRoute.Albums),
            syncedBackStack(listOf(NextGalleryRoute.Albums), session),
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
        assertEquals("photos", NextGalleryRoute.Photos.navContentKey())
        assertEquals("albums", NextGalleryRoute.Albums.navContentKey())
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

    @Test fun `album destination survives serialization and is removed on logout`() {
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
        assertEquals(stack, syncedBackStack(stack, SessionUiState.SignedIn(credentials())))
        assertEquals(
            listOf(NextGalleryRoute.Login),
            syncedBackStack(stack, SessionUiState.SignedOut),
        )
    }

    private fun credentials(): AccountCredentials =
        AccountCredentials(
            serverUrl = "https://cloud.example.com",
            loginName = "user",
            appPassword = "secret",
        )
}

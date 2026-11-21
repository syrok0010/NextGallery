package com.syrok0010.nextgallery.router

import org.junit.Assert.*
import org.junit.Test

class AppNavigatorTest {
    @Test fun `commands wait without a host and are consumed once in order`() {
        val navigator = AppNavigator()
        navigator.open(NextGalleryRoute.Uploads)
        navigator.back()
        val first = navigator.commands.value.first()
        val received = mutableListOf<NavigationCommand>()
        navigator.consume(first.id) { received += it }
        // A stale collector from a recreated host cannot replay an acknowledged command.
        navigator.consume(first.id) { error("replayed") }
        val second = navigator.commands.value.single()
        navigator.consume(second.id) { received += it }
        assertEquals(
            listOf(NavigationCommand.Open(NextGalleryRoute.Uploads), NavigationCommand.Back),
            received,
        )
        assertTrue(navigator.commands.value.isEmpty())
    }

    @Test fun `command arriving during consumption stays queued`() {
        val navigator = AppNavigator()
        navigator.open(NextGalleryRoute.Uploads)
        val first = navigator.commands.value.single()
        navigator.consume(first.id) { navigator.back() }
        assertEquals(NavigationCommand.Back, navigator.commands.value.single().command)
    }

    @Test fun `failed host action leaves command pending for the next host`() {
        val navigator = AppNavigator()
        navigator.open(NextGalleryRoute.Uploads)
        val pending = navigator.commands.value.single()
        assertThrows(IllegalStateException::class.java) {
            navigator.consume(pending.id) { error("host failed") }
        }
        assertEquals(listOf(pending), navigator.commands.value)
    }

    @Test fun `navigation keeps root and selects existing tabs without duplicates`() {
        val photos = listOf(NextGalleryRoute.Photos)
        val uploads =
            applyNavigationCommand(photos, NavigationCommand.Open(NextGalleryRoute.Uploads))
        assertEquals(
            uploads,
            applyNavigationCommand(uploads, NavigationCommand.Open(NextGalleryRoute.Uploads)),
        )
        assertEquals(photos, applyNavigationCommand(uploads, NavigationCommand.Back))
        assertEquals(photos, applyNavigationCommand(photos, NavigationCommand.Back))
        val albums =
            applyNavigationCommand(photos, NavigationCommand.Select(NextGalleryRoute.Albums))
        assertEquals(
            photos,
            applyNavigationCommand(albums, NavigationCommand.Select(NextGalleryRoute.Photos)),
        )
    }
}

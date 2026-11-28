package com.syrok0010.nextgallery.feature.albums

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.app.ui.*
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import com.syrok0010.nextgallery.string
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumCatalogUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cardsStretchToTallestInRowWithoutMovingCoversAndOpenTheirOwnAlbum() {
        var opened: AlbumSummary? = null
        val albums = listOf(
            AlbumSummary(
                "short",
                "Отпуск",
                AlbumOrigin.Nextcloud,
                6,
                "Анна",
                location = AlbumLocation.Remote("anna/Отпуск"),
            ),
            AlbumSummary(
                "long",
                "Отпуск с очень длинным названием папки",
                AlbumOrigin.Phone,
                12,
                "Pictures/Путешествия/Отпуск/ · external_primary",
                location = AlbumLocation.Folder("external_primary", "Pictures/Путешествия/Отпуск/"),
            ),
        )
        rule.setContent {
            NextGalleryTheme {
                Box(
                    Modifier.width(340.dp),
                ) { AlbumCardRow(albums, onOpen = { opened = it }, cover = {}) }
            }
        }
        val left = rule.onNodeWithTag("album_card:short").fetchSemanticsNode().boundsInRoot
        val right = rule.onNodeWithTag("album_card:long").fetchSemanticsNode().boundsInRoot
        assertEquals(left.height, right.height, .5f)
        assertEquals(left.top, right.top, .5f)
        rule.onNodeWithTag("album_card:short").performClick()
        rule.runOnIdle { assertEquals(albums[0], opened) }
        rule.onNodeWithTag("album_card:long").performClick()
        rule.runOnIdle { assertEquals(albums[1], opened) }
    }

    @Test fun localIndexingShowsLoaderOnlyWhenLocalSectionIsVisible() {
        val state = AlbumsUiState(
            local = AlbumSourceState(
                loading = true,
                progress = AlbumLoadingProgress(loaded = 200, total = 1_000),
            ),
            permission = com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode.Full,
        )
        var filter: AlbumOrigin? = null
        rule.setContent {
            NextGalleryTheme {
                AlbumsPanel(
                    state = state,
                    listState = androidx.compose.foundation.lazy.rememberLazyListState(),
                    filter = filter,
                    onFilter = { filter = it },
                    onRetry = {},
                    onOpen = {},
                )
            }
        }
        rule.onNodeWithTag("albums_loader").assertIsDisplayed()
        rule.onNodeWithTag("albums_local_indexing").assertIsDisplayed()
    }
}

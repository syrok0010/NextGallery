package com.syrok0010.nextgallery.feature.albums

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
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
                "Pictures/Путешествия/Отпуск",
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

    @Test fun initialIndexingShowsProgressAndOneTimeNoteWhileReindexShowsSpinnerOnly() {
        var reindexing by mutableStateOf(false)
        rule.setContent {
            NextGalleryTheme {
                Column {
                    LibraryHeader(
                        destination = TopLevelDestination.Photos,
                        onRefresh = {},
                        onLogout = {},
                        reindexing = reindexing,
                    )
                    if (!reindexing) {
                        LocalIndexingBanner(
                            com.syrok0010.nextgallery.feature.library.LocalLibraryStatus(
                                loading = true,
                                initial = true,
                                progress = com.syrok0010.nextgallery.feature.library.local
                                    .LocalMediaIndexProgress(
                                        indexedCount = 200,
                                        totalCount = 1_000,
                                    ),
                            ),
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("local_indexing_banner").assertIsDisplayed()
        rule.onNodeWithTag("local_indexing_progress").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.local_indexing_one_time_note)).assertIsDisplayed()
        rule.onNodeWithTag("local_indexing_spinner").assertDoesNotExist()

        rule.runOnIdle { reindexing = true }
        rule.onNodeWithTag("local_indexing_spinner").assertIsDisplayed()
        rule.onNodeWithTag("local_indexing_banner").assertDoesNotExist()
    }
}

package com.syrok0010.nextgallery.feature.albums

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.app.ui.*
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.timeline.AuthenticatedUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumCatalogUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cardsStretchToTallestInRowWithoutMovingCoversAndDoNotOpen() {
        val albums = listOf(
            AlbumSummary("short", "Отпуск", AlbumOrigin.Nextcloud, 6, "Анна"),
            AlbumSummary("long", "Отпуск с очень длинным названием папки", AlbumOrigin.Phone, 12,
                "Pictures/Путешествия/Отпуск/ · external_primary"),
        )
        rule.setContent {
            NextGalleryTheme {
                Box(Modifier.width(340.dp)) { AlbumCardRow(albums, cover = {}) }
            }
        }
        val left = rule.onNodeWithTag("album_card:short").fetchSemanticsNode().boundsInRoot
        val right = rule.onNodeWithTag("album_card:long").fetchSemanticsNode().boundsInRoot
        assertEquals(left.height, right.height, .5f)
        assertEquals(left.top, right.top, .5f)
        rule.onNodeWithTag("album_card:short").assertHasNoClickAction()
        rule.onNodeWithTag("album_card:long").assertHasNoClickAction()
    }

    @Test fun menuOpensLiveDiagnosticsAndNavigationChangesSelection() {
        val state = mutableStateOf(AuthenticatedUiState(sourceDiagnostics = listOf(uiText(R.string.status_indexing_local_media, 1, 100))))
        val diagnostics = mutableStateOf(false)
        val page = mutableStateOf(LibraryPage.Photos)
        rule.setContent {
            NextGalleryTheme(darkTheme = true, dynamicColor = false) {
                Column {
                    LibraryHeader(page.value, false, { diagnostics.value = true }, {}, {})
                    LibraryIsland(page.value, { page.value = it })
                }
                if (diagnostics.value) LibraryDiagnostics(state.value, AlbumsUiState(), { diagnostics.value = false })
            }
        }
        rule.onNodeWithTag("library_page:Albums").performClick()
        rule.runOnIdle { assertEquals(LibraryPage.Albums, page.value) }
        rule.onNodeWithTag("library_menu").performClick()
        rule.onNodeWithText("Диагностика").performClick()
        rule.onNodeWithText("Индексирую фото и видео с устройства: 1 из 100").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(sourceDiagnostics = listOf(uiText(R.string.status_indexing_local_media, 88, 100))) }
        rule.onNodeWithText("Индексирую фото и видео с устройства: 88 из 100").assertIsDisplayed()
    }
}

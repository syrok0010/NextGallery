package com.syrok0010.nextgallery

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.core.locale.initializeAppLanguage
import com.syrok0010.nextgallery.core.locale.withAppLanguage
import com.syrok0010.nextgallery.feature.collection.MediaDayHeader
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationLocaleTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun russianPluralsAndDatesWithEnglishSystem() {
        val application = ApplicationProvider.getApplicationContext<NextGalleryApplication>()
        // Resources.getSystem() follows the process app locale on Android 13+, not the device.
        val systemLocales = if (Build.VERSION.SDK_INT >= 33) {
            application.getSystemService(LocaleManager::class.java).systemLocales.toLanguageTags()
        } else {
            Settings.System.getString(application.contentResolver, "system_locales")
        }
        assumeTrue("Run on an English-system emulator", systemLocales.startsWith("en"))
        assertRussianPlurals(application)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertRussianPlurals(activity)
                activity.setContent {
                    Column {
                        MediaDayHeader(LocalDate.of(2026, 10, 31).toEpochDay().toInt())
                        Text(pluralStringResource(R.plurals.albums_count, 2, 2))
                    }
                }
            }
            compose.onNodeWithText("31 октября").assertIsDisplayed()
            compose.onNodeWithText("2 элемента").assertIsDisplayed()
        }
    }

    @Test
    fun englishConfigurationUsesRussianFallbackForNonActivityResources() {
        val application = ApplicationProvider.getApplicationContext<NextGalleryApplication>()
        val english = application.createConfigurationContext(
            Configuration().apply {
                setLocales(LocaleList.forLanguageTags("en-US"))
            },
        )
        assertEquals("en", english.resources.configuration.locales[0].language)
        assertRussianPlurals(english.withAppLanguage())
        val previous = Configuration(application.resources.configuration)
        try {
            application.onConfigurationChanged(english.resources.configuration)
            assertRussianPlurals(application)
        } finally {
            application.onConfigurationChanged(previous)
        }
    }

    @Test
    fun platformLanguageChoiceIsNotOverwrittenAfterInitialization() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val application = ApplicationProvider.getApplicationContext<NextGalleryApplication>()
        val manager = application.getSystemService(LocaleManager::class.java)
        val previous = manager.applicationLocales
        val preferences = application.getSharedPreferences("app_language", Context.MODE_PRIVATE)
        val previousInitialized = if (preferences.contains("initialized")) {
            preferences.getBoolean("initialized", false)
        } else {
            null
        }
        try {
            // Prepare initialization explicitly, regardless of a previous run's language choice.
            preferences.edit().remove("initialized").commit()
            manager.applicationLocales = LocaleList.getEmptyLocaleList()
            application.initializeAppLanguage()
            assertEquals("ru", manager.applicationLocales.toLanguageTags())
            manager.applicationLocales = LocaleList.getEmptyLocaleList()
            application.initializeAppLanguage()
            assertEquals("", manager.applicationLocales.toLanguageTags())
            assertRussianPlurals(application)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { assertRussianPlurals(it) }
                scenario.recreate()
                scenario.onActivity { assertRussianPlurals(it) }
            }
            assertEquals("", manager.applicationLocales.toLanguageTags())
            manager.applicationLocales = LocaleList.forLanguageTags("ru-RU")
            application.initializeAppLanguage()
            assertEquals("ru-RU", manager.applicationLocales.toLanguageTags())
        } finally {
            manager.applicationLocales = previous
            preferences
                .edit()
                .apply {
                    if (previousInitialized == null) {
                        remove("initialized")
                    } else {
                        putBoolean("initialized", previousInitialized)
                    }
                }.commit()
        }
    }

    @Test
    fun existingPlatformLanguageIsPreservedOnFirstInitialization() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val application = ApplicationProvider.getApplicationContext<NextGalleryApplication>()
        val manager = application.getSystemService(LocaleManager::class.java)
        val previous = manager.applicationLocales
        try {
            application
                .getSharedPreferences("app_language", Context.MODE_PRIVATE)
                .edit()
                .remove("initialized")
                .commit()
            manager.applicationLocales = LocaleList.forLanguageTags("ru-RU")
            application.initializeAppLanguage()
            assertEquals("ru-RU", manager.applicationLocales.toLanguageTags())
        } finally {
            manager.applicationLocales = previous
            application.initializeAppLanguage()
        }
    }

    private fun assertRussianPlurals(context: Context) {
        mapOf(
            0 to "0 элементов",
            1 to "1 элемент",
            2 to "2 элемента",
            4 to "4 элемента",
            5 to "5 элементов",
            11 to "11 элементов",
            21 to "21 элемент",
            22 to "22 элемента",
            25 to "25 элементов",
        ).forEach { (count, expected) ->
            assertEquals(
                expected,
                context.resources.getQuantityString(R.plurals.albums_count, count, count),
            )
        }
    }
}

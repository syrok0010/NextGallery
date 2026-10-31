package com.syrok0010.nextgallery.core.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/** Russian is the only supported language, including when the system language is unsupported. */
internal fun Configuration.withAppLanguage(): Configuration =
    Configuration(this).apply {
        if (locales.isEmpty || locales[0].language != "ru") {
            setLocales(LocaleList.forLanguageTags("ru"))
        }
    }

internal fun Context.withAppLanguage(): Context =
    createConfigurationContext(
        Configuration().apply {
            setLocales(resources.configuration.withAppLanguage().locales)
        },
    )

/** Initialize once: a later choice of "System default" must remain an empty locale list. */
internal fun Context.initializeAppLanguage() {
    if (Build.VERSION.SDK_INT < 33) return
    val preferences = getSharedPreferences("app_language", Context.MODE_PRIVATE)
    if (preferences.getBoolean("initialized", false)) return
    val manager = getSystemService(LocaleManager::class.java)
    if (manager.applicationLocales.isEmpty) {
        manager.applicationLocales = LocaleList.forLanguageTags("ru")
    }
    preferences.edit().putBoolean("initialized", true).apply()
}

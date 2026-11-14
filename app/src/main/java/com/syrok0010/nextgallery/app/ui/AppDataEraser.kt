package com.syrok0010.nextgallery.app.ui

import android.app.ActivityManager
import android.content.Context

/** Logout: leaves the app as freshly installed (ADR 0010). */
internal fun interface AppDataEraser {
    /** Erases every app file, database and preference; throws when the system refuses. */
    fun erase()
}

/** The system erases the data and ends the process, so no late write can follow. */
internal class SystemAppDataEraser(private val context: Context) : AppDataEraser {
    override fun erase() {
        val activities = context.getSystemService(ActivityManager::class.java)
        check(activities.clearApplicationUserData()) { "The system did not erase the app data" }
    }
}

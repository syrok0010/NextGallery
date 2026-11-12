package com.syrok0010.nextgallery

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

/** An app string in the language the tests run in. */
fun string(@StringRes id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

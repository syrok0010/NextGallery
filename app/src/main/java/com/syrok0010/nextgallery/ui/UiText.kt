package com.syrok0010.nextgallery.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

sealed interface UiText {
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText {
    return UiText.Resource(id = id, args = args.toList())
}

@Composable
fun UiText.asString(): String {
    return when (this) {
        is UiText.Resource -> stringResource(id, *args.toTypedArray())
    }
}

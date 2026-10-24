package com.syrok0010.nextgallery.core.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

sealed interface UiText {
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** [quantity] picks the plural form; [args] still have to include the number to show it. */
    data class Plural(
        @param:PluralsRes val id: Int,
        val quantity: Int,
        val args: List<Any> = emptyList(),
    ) : UiText
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText =
    UiText.Resource(id = id, args = args.toList())

fun pluralText(@PluralsRes id: Int, quantity: Int, vararg args: Any): UiText =
    UiText.Plural(id = id, quantity = quantity, args = args.toList())

@Composable
fun UiText.asString(): String =
    when (this) {
        is UiText.Resource -> stringResource(id, *args.toTypedArray())
        is UiText.Plural -> pluralStringResource(id, quantity, *args.toTypedArray())
    }

package com.syrok0010.nextgallery

import android.net.Uri

/**
 * Links into app screens. Notifications open them through [MainActivity]; the app maps each link
 * to its route, so a new destination needs a link, not another launch flag.
 */
object AppLinks {
    val Uploads: Uri = Uri.parse("nextgallery://uploads")
}

package com.syrok0010.nextgallery.feature.deletion

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A row gone from MediaStore was not moved to the trash, so it is not counted as trashed. */
internal class AndroidPhoneTrash(private val resolver: ContentResolver) : PhoneTrashCheck {
    override suspend fun trashed(contentUris: List<String>): Int =
        withContext(Dispatchers.IO) {
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            contentUris.count { uri ->
                resolver
                    .query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)
                    ?.use { it.moveToFirst() && it.getInt(0) == 1 } == true
            }
        }
}

/** Android 16 rejects larger trash requests outright; older releases have no documented limit. */
internal const val MAX_TRASH_REQUEST = 2000

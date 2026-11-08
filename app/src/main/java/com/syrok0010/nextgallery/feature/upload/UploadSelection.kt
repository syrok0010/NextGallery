package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Phone copy of one object, fixed by [UploadQueue.submit] when the object is queued. */
@Serializable
internal data class UploadSelection(
    @Serializable(with = LocalContentSerializer::class) val local: MediaAssetRef.LocalContent,
    val displayName: String,
    val mimeType: String?,
    val stamp: SourceStamp? = null,
) {
    val contentUri: String get() = local.contentUri

    /** The URI the device-wide media index and identity links use for this file. */
    val identityUri: String get() = uploadedLocalIdentityUri(contentUri)
}

/** Keeps the phone copy in the journal while core/media stays free of serialization. */
private object LocalContentSerializer : KSerializer<MediaAssetRef.LocalContent> {
    @Serializable
    @SerialName("LocalContent")
    private class Stored(
        val contentUri: String,
        val modifiedAtEpochSeconds: Long?,
        val sizeBytes: Long,
    )

    override val descriptor = Stored.serializer().descriptor

    override fun serialize(encoder: Encoder, value: MediaAssetRef.LocalContent) =
        encoder.encodeSerializableValue(
            Stored.serializer(),
            Stored(value.contentUri, value.modifiedAtEpochSeconds, value.sizeBytes),
        )

    override fun deserialize(decoder: Decoder) =
        decoder.decodeSerializableValue(Stored.serializer()).let {
            MediaAssetRef.LocalContent(it.contentUri, it.modifiedAtEpochSeconds, it.sizeBytes)
        }
}

/** Only objects that exist just on the phone are uploaded; others already have a server copy. */
internal fun uploadSelection(item: MediaItem): UploadSelection? =
    item.localCopy?.takeUnless { item.hasRemoteCopy }?.let { local ->
        UploadSelection(
            local = local,
            displayName = item.displayName,
            mimeType = item.mimeType,
        )
    }

/**
 * [selection] is on the server at [path]: uploaded now ([created]) or already there with the same
 * bytes. [path] may be a neighbour name when the original one holds a different file. [fileId] is
 * the server file id when DAV returned it.
 */
internal data class UploadedFile(
    val selection: UploadSelection,
    val path: DavFilePath,
    val created: Boolean,
    val fileId: Long? = null,
)

/** A failure the user can act on; [httpStatus] is the server answer when there was one. */
internal class UploadException(val problem: UploadProblem, val httpStatus: Int? = null) :
    Exception()

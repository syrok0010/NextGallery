package com.syrok0010.nextgallery.core.network.dav

import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlException
import nl.adaptivity.xmlutil.core.KtXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/*
 * WebDAV bodies as serializable models. Only the elements read or sent here are declared: unknown
 * ones (`href`, other properties) are skipped. Values stay strings, because a `propstat` with a
 * non-200 status lists the requested properties as empty elements.
 */

private const val DAV = "DAV:"
private const val OWNCLOUD = "http://owncloud.org/ns"

/** `PROPFIND` body asking for the properties of [FileProps]. */
@Serializable
@XmlSerialName("propfind", DAV, "d")
internal data class PropFind(@XmlElement val prop: RequestedFileProps = RequestedFileProps())

@Serializable
@XmlSerialName("prop", DAV, "d")
internal data class RequestedFileProps(
    @XmlElement @XmlSerialName("getcontentlength", DAV, "d")
    val size: Requested = Requested,
    @XmlElement @XmlSerialName("getetag", DAV, "d")
    val etag: Requested = Requested,
    @XmlElement @XmlSerialName("fileid", OWNCLOUD, "oc")
    val fileId: Requested = Requested,
)

/** An empty element naming a property to return. */
@Serializable
internal data object Requested

@Serializable
@XmlSerialName("multistatus", DAV, "d")
internal data class Multistatus(@XmlElement val responses: List<MultistatusResponse> = emptyList())

@Serializable
@XmlSerialName("response", DAV, "d")
internal data class MultistatusResponse(@XmlElement val propstats: List<PropStat> = emptyList())

@Serializable
@XmlSerialName("propstat", DAV, "d")
internal data class PropStat(
    @XmlElement val prop: FileProps = FileProps(),
    @XmlElement @XmlSerialName("status", DAV, "d")
    val status: String = "",
) {
    /** `HTTP/1.1 200 OK`: the properties in [prop] were found. */
    val found: Boolean get() = status.trim().split(Regex("\\s+")).getOrNull(1) == "200"
}

@Serializable
@XmlSerialName("prop", DAV, "d")
internal data class FileProps(
    @XmlElement @XmlSerialName("getcontentlength", DAV, "d")
    val size: String? = null,
    @XmlElement @XmlSerialName("getetag", DAV, "d")
    val etag: String? = null,
    @XmlElement @XmlSerialName("fileid", OWNCLOUD, "oc")
    val fileId: String? = null,
)

private val davXml = XML {
    // The pure Kotlin parser: the same code on Android and in JVM tests, and it never loads
    // external entities.
    defaultToGenericParser = true
    defaultPolicy { ignoreUnknownChildren() }
}

internal fun encodeDav(body: PropFind): String = davXml.encodeToString(PropFind.serializer(), body)

/** Fails with [IOException] on a malformed body or one that declares a DOCTYPE. */
internal fun decodeMultistatus(body: InputStream): Multistatus =
    try {
        val reader = KtXmlReader(body, expandEntities = false)
        while (reader.next() != EventType.START_ELEMENT) {
            if (reader.eventType == EventType.DOCDECL) {
                throw IOException("DAV response declares a DOCTYPE")
            }
        }
        davXml.decodeFromReader(Multistatus.serializer(), reader)
    } catch (error: XmlException) {
        throw IOException("Malformed DAV response", error)
    } catch (error: SerializationException) {
        throw IOException("Malformed DAV response", error)
    }

package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.await
import com.syrok0010.nextgallery.core.network.fileUrl
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.upload.AlbumStepMode
import com.syrok0010.nextgallery.feature.upload.UploadIntent
import com.syrok0010.nextgallery.feature.upload.UploadStepResult
import com.syrok0010.nextgallery.feature.upload.UploadedFile
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Photos exposes membership, but no conditional destination collection identity for COPY.
 * Initial execution may COPY once; recovery only reconciles proven membership.
 */
internal class DurableAlbumStep(private val transport: NextcloudTransport) {
    private val client = transport.baseClient.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    suspend fun snapshot(account: AccountCredentials, intent: UploadIntent): UploadIntent = withContext(Dispatchers.IO) {
        if (intent.kind != "album" || intent.version != 1 || intent.owner != account.userId) return@withContext intent
        val url = albumUrl(account.serverUrl, AlbumAddress(intent.owner, intent.target))
        val id = try { albumId(account, intent) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        intent.copy(version = 2, albumId = id?.takeIf { intent.albumId == null || intent.albumId == it }, albumHref = url.toString())
    }

    suspend fun run(
        account: AccountCredentials,
        intent: UploadIntent,
        file: UploadedFile,
        mode: AlbumStepMode = AlbumStepMode.Reconcile,
        current: suspend () -> Boolean,
    ): UploadStepResult = withContext(Dispatchers.IO) {
        var copyStarted = false
        try {
            if (intent.version != 2 || intent.albumId == null || intent.albumId <= 0 || intent.albumHref == null) return@withContext attention("LegacyTarget")
            if (intent.owner != account.userId) return@withContext attention("Owner")
            val album = albumUrl(account.serverUrl, AlbumAddress(intent.owner, intent.target))
            if (intent.albumHref != album.toString()) return@withContext attention("TargetChanged")
            val root = fileUrl(account.serverUrl, account.userId, "")
            if (!file.url.sameOrigin(root) || file.url.encodedPath == root.encodedPath ||
                !file.url.encodedPath.startsWith(root.encodedPath.trimEnd('/') + "/") ||
                file.url.query != null || file.url.fragment != null || file.url.username.isNotEmpty() || file.url.password.isNotEmpty()) return@withContext attention("SourceChanged")
            val fileId = file.fileId?.takeIf { it > 0 } ?: return@withContext attention("SourceChanged")
            if (!current()) return@withContext attention("Stopped")
            if (albumId(account, intent) != intent.albumId) return@withContext attention("TargetChanged")
            val source = propfind(account, file.url, "0").singleOrNull() ?: return@withContext attention("SourceChanged")
            if (source.href != file.url || source.text(OC, "fileid")?.toLongOrNull() != fileId ||
                source.text(OC, "owner-id") != account.userId || !source.file() ||
                source.text(OC, "permissions")?.contains('G') != true) return@withContext attention("SourceChanged")
            val relative = "/" + file.url.pathSegments.drop(root.pathSegments.filter { it.isNotEmpty() }.size).joinToString("/")
            suspend fun reconcile(): UploadStepResult? {
                val entries = propfind(account, album, "1")
                val target = entries.singleOrNull { it.href.encodedPath.trimEnd('/') == album.encodedPath.trimEnd('/') }
                    ?: return attention("Membership")
                if (!target.href.sameOrigin(album) || !target.collection() || target.text(NC, "original-name") != intent.target ||
                    !target.privilege("read") || !target.privilege("bind")) return attention("Permission")
                var member: DavEntry? = null
                for (entry in entries.filter { it !== target }) {
                    if (!entry.href.sameOrigin(album) || entry.href.encodedPath.substringBeforeLast('/') != album.encodedPath.trimEnd('/') || !entry.file()) return attention("Membership")
                    val id = entry.text(OC, "fileid")?.toLongOrNull()?.takeIf { it > 0 } ?: return attention("Membership")
                    val original = entry.text(NC, "photos-collection-file-original-filename") ?: return attention("Membership")
                    if (!entry.href.pathSegments.last().startsWith("$id-")) return attention("Membership")
                    if (id == fileId || original == relative) {
                        val expected = album.newBuilder().addPathSegment("$fileId-${file.url.pathSegments.last()}").build()
                        if (id != fileId || original != relative || entry.href != expected || member != null) return attention("SourceChanged")
                        member = entry
                    }
                }
                if (albumId(account, intent) != intent.albumId) return attention("TargetChanged")
                return member?.let { UploadStepResult("Done", fileId = fileId, targetId = intent.albumId, href = it.href.toString()) }
            }
            reconcile()?.let { return@withContext it }
            if (mode != AlbumStepMode.Initial) return@withContext attention("ReplayUnsupported")
            val etag = source.text(DAV, "getetag")
                ?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') && !it.contains('\r') && !it.contains('\n') }
                ?: return@withContext attention("SourceChanged")
            val destination = album.newBuilder().addPathSegment(file.url.pathSegments.last()).build()
            val request = transport.authenticatedRequestBuilder(account, file.url.toString())
                .method("COPY", null).header("Destination", destination.toString())
                .header("If-Match", etag).header("Overwrite", "F").build()
            if (!current()) return@withContext attention("Stopped")
            // This permission lives only in the pass that durably accepted the receipt.
            // The server cannot atomically fence a concurrently recreated destination.
            copyStarted = true
            val status = client.newCall(request).await().use { it.code }
            val result = reconcile()
            if (result?.state == "Done") return@withContext result
            if (status in 400..499) return@withContext result ?: attention("CopyRejected")
            UploadStepResult.uncertain()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (copyStarted) UploadStepResult.uncertain() else attention("Contract")
        }
    }

    private suspend fun albumId(account: AccountCredentials, intent: UploadIntent): Long? {
        val url = NextcloudTransport.normalizeServerOrigin(account.serverUrl) + "/apps/memories/api/clusters/albums"
        val request = transport.authenticatedRequestBuilder(account, url).build()
        return client.newCall(request).await().use { response ->
            check(response.code == 200)
            val albums = Json.parseToJsonElement(response.body!!.string()) as JsonArray
            val matches = albums.map { it as JsonObject }.filter {
                it.text("cluster_id") == "${intent.owner}/${intent.target}" && it.text("user") == intent.owner && it.text("name") == intent.target
            }
            matches.singleOrNull()?.text("album_id")?.toLongOrNull()?.takeIf { it > 0 }
        }
    }

    private suspend fun propfind(account: AccountCredentials, url: HttpUrl, depth: String): List<DavEntry> {
        val request = transport.authenticatedRequestBuilder(account, url.toString(), "application/xml")
            .method("PROPFIND", PROPERTIES.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth).build()
        return client.newCall(request).await().use { response ->
            check(response.code == 207)
            val source = response.body!!.source()
            check(!source.request(1024 * 1024L + 1))
            val xml = source.readUtf8()
            // Parse a bounded response; reject DTDs before the parser can expand entities.
            check(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
            val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val document = builder.parse(InputSource(StringReader(xml)))
            val root = document.documentElement
            check(root.namespaceURI == DAV && root.localName == "multistatus")
            val responses = root.children(DAV, "response")
            check(responses.isNotEmpty())
            responses.map { entry ->
                check(entry.children(DAV, "status").isEmpty())
                val rawHref = entry.children(DAV, "href").single().textContent
                val href = requireNotNull(url.resolve(rawHref))
                check(href.sameOrigin(url) && href.query == null && href.fragment == null && href.username.isEmpty() && href.password.isEmpty())
                val props = mutableMapOf<Pair<String, String>, Element>()
                entry.children(DAV, "propstat").forEach { stat ->
                    val status = stat.children(DAV, "status").single().textContent.trim()
                    check(status.matches(Regex("HTTP/1\\.[01] (200|404) .+")))
                    val property = stat.children(DAV, "prop").single()
                    property.elements().forEach { prop ->
                        val key = prop.namespaceURI to prop.localName
                        // A property repeated across success/error propstats is ambiguous.
                        check(key !in props)
                        props[key] = if (status.split(' ')[1] == "200") prop else FAILED_PROPERTY
                    }
                }
                DavEntry(href, props.filterValues { it !== FAILED_PROPERTY })
            }.also { entries -> check(entries.map { it.href }.distinct().size == entries.size) }
        }
    }

    private data class DavEntry(val href: HttpUrl, val props: Map<Pair<String, String>, Element>) {
        fun text(ns: String, key: String) = props[ns to key]?.takeIf { it.elements().isEmpty() }?.textContent?.trim()
        fun file() = props[DAV to "resourcetype"]?.elements()?.isEmpty() == true
        fun collection() = props[DAV to "resourcetype"]?.children(DAV, "collection")?.size == 1
        fun privilege(name: String) = props[DAV to "current-user-privilege-set"]?.children(DAV, "privilege")?.any { it.children(DAV, name).size == 1 } == true
    }

    private companion object {
        const val DAV = "DAV:"
        const val OC = "http://owncloud.org/ns"
        const val NC = "http://nextcloud.org/ns"
        val FAILED_PROPERTY = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument().createElement("failed")
        const val PROPERTIES = """<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop><d:resourcetype/><d:getetag/><d:current-user-privilege-set/><oc:fileid/><oc:permissions/><oc:owner-id/><nc:original-name/><nc:photos-collection-file-original-filename/></d:prop></d:propfind>"""
        fun attention(problem: String) = UploadStepResult.attention(problem)
        fun HttpUrl.sameOrigin(other: HttpUrl) = scheme == other.scheme && host == other.host && port == other.port
        fun Element.elements() = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        fun Element.children(ns: String, name: String) = elements().filter { it.namespaceURI == ns && it.localName == name }
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content
    }
}

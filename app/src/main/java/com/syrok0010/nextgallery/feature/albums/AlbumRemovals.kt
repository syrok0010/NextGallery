package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.await
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Removing membership never addresses the original in DAV files or the album collection itself. */
internal class MemoriesAlbumRemover(private val transport: NextcloudTransport) {
    private val client = transport.baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    suspend fun remove(
        credentials: AccountCredentials,
        location: AlbumLocation.Remote,
        fileIds: Set<Long>,
    ): AlbumRemovalResult = withContext(Dispatchers.IO) {
        val address = requireNotNull(AlbumAddress.of(location))
        require(address.owner == credentials.userId) { "Only owned albums support removal" }
        val root = albumUrl(credentials.serverUrl, address)
        val request = transport.authenticatedRequestBuilder(credentials, root.toString(), "application/xml")
            .method("PROPFIND", PROPERTIES.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1").build()
        val members = client.newCall(request).await().use {
            if (it.code != 207) throw IOException("HTTP ${it.code} listing album membership")
            albumMemberUrls(root, it.body.string())
        }
        val permits = Semaphore(4)
        val outcomes = coroutineScope {
            fileIds.map { id ->
                async {
                    permits.withPermit {
                        val url = members[id] ?: return@withPermit true
                        try {
                            val delete = transport.authenticatedRequestBuilder(credentials, url.toString(), "*/*")
                                .delete().build()
                            client.newCall(delete).await().use { it.isSuccessful }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
            }.awaitAll()
        }
        AlbumRemovalResult(outcomes.count { it }, outcomes.count { !it })
    }

    private companion object {
        const val PROPERTIES = """<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:fileid/></d:prop></d:propfind>"""
    }
}

/** Accept only exact immediate children of this album on this server, even for hostile DAV XML. */
internal fun albumMemberUrls(root: HttpUrl, xml: String): Map<Long, HttpUrl> {
    require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
    val builder = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> throw IOException("External XML entities are forbidden") }
    }
    val doc = builder.parse(InputSource(StringReader(xml)))
    require(doc.documentElement.localName == "multistatus" && doc.documentElement.namespaceURI == "DAV:")
    val responses = doc.getElementsByTagNameNS("DAV:", "response")
    val parent = root.encodedPath.trimEnd('/') + "/"
    return buildMap {
        for (index in 0 until responses.length) {
            val response = responses.item(index) as Element
            val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent
                ?: throw IOException("Missing membership href")
            val url = root.resolve(href) ?: throw IOException("Invalid membership href")
            require(url.scheme == root.scheme && url.host == root.host && url.port == root.port)
            if (url.encodedPath.trimEnd('/') == root.encodedPath.trimEnd('/')) continue
            require(url.encodedPath.startsWith(parent) && '/' !in url.encodedPath.removePrefix(parent))
            require(url.query == null && url.fragment == null)
            val propstats = response.getElementsByTagNameNS("DAV:", "propstat")
            var fileId: Long? = null
            for (propIndex in 0 until propstats.length) {
                val propstat = propstats.item(propIndex) as Element
                val status = propstat.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent
                if (status?.split(' ')?.getOrNull(1) == "200") {
                    fileId = propstat.getElementsByTagNameNS("http://owncloud.org/ns", "fileid")
                        .item(0)?.textContent?.toLongOrNull() ?: fileId
                }
            }
            val id = requireNotNull(fileId) { "Missing membership file id" }
            require(id > 0 && url.pathSegments.last().startsWith("$id-"))
            require(put(id, url) == null) { "Duplicate membership file id" }
        }
    }
}

internal data class AlbumRemovalResult(val removed: Int, val failed: Int)
internal data class AlbumRemovalProgress(val name: String, val fileIds: Set<Long>)
internal data class AlbumRemovalOutcome(val name: String, val result: AlbumRemovalResult)

/** Session/process owned, so navigation and recreation cannot lose a removal or its result. */
internal class AlbumRemovals(
    private val sessions: SessionStore,
    private val remover: MemoriesAlbumRemover,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) {
    private val mutableProgress = MutableStateFlow<AlbumRemovalProgress?>(null)
    val progress = mutableProgress.asStateFlow()
    private val mutableResult = MutableStateFlow<AlbumRemovalOutcome?>(null)
    val result = mutableResult.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch {
            var owner: AccountCredentials? = null
            sessions.session.collect { session ->
                val current = (session as? SessionUiState.SignedIn)?.credentials
                if (current == null || (owner != null && owner != current)) {
                    job?.cancel()
                    mutableProgress.value = null
                    mutableResult.value = null
                }
                owner = current
            }
        }
    }

    fun remove(location: AlbumLocation.Remote, name: String, fileIds: Set<Long>): Boolean {
        if (job?.isActive == true || fileIds.isEmpty()) return false
        val credentials = (sessions.session.value as? SessionUiState.SignedIn)?.credentials ?: return false
        if (AlbumAddress.of(location)?.owner != credentials.userId) return false
        mutableProgress.value = AlbumRemovalProgress(name, fileIds)
        job = scope.launch {
            val result = try {
                remover.remove(credentials, location, fileIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AlbumRemovalResult(0, fileIds.size)
            }
            mutableResult.value = mutableResult.value?.let {
                // Keep unacknowledged outcomes, including failures from a previous removal.
                AlbumRemovalOutcome(name, AlbumRemovalResult(it.result.removed + result.removed, it.result.failed + result.failed))
            } ?: AlbumRemovalOutcome(name, result)
            mutableProgress.value = null
            onChanged()
        }
        return true
    }

    fun consume() { mutableResult.value = null }
}

/** Foreign Photos albums have no verified mutation/permission contract in this client. */
internal fun supportsAlbumActions(location: AlbumLocation, user: String?): Boolean =
    location is AlbumLocation.Folder ||
        (user != null && location is AlbumLocation.Remote && AlbumAddress.of(location)?.owner == user)

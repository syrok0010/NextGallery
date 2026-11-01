package com.syrok0010.nextgallery.feature.favorites

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.await
import com.syrok0010.nextgallery.core.network.fileUrl
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import retrofit2.http.GET
import retrofit2.http.Path

@Serializable
internal data class FavoriteFileInfo(
    val fileid: Long,
    val filename: String,
    val basename: String,
    val owneruid: String,
    val etag: String,
    val permissions: String,
)
internal interface FavoriteFilesApi {
    @GET("apps/memories/api/image/info/{id}")
    suspend fun info(@Path("id") fileId: Long): FavoriteFileInfo
}

internal class MemoriesFavoriteWriter(private val transport: NextcloudTransport) {
    // Never follow a redirect with the app password, including the file-id lookup.
    private val client = transport.baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun set(credentials: AccountCredentials, fileId: Long, favorite: Boolean) {
        require(fileId > 0)
        val api = transport.retrofit(
            transport.normalizeBaseUrl(credentials.serverUrl),
            client.newBuilder().addInterceptor { chain ->
                chain.proceed(NextcloudTransport.applyAuthenticatedHeaders(chain.request().newBuilder(), credentials).build())
            }.build(),
        ).create(FavoriteFilesApi::class.java)
        val info = api.info(fileId)
        require(info.fileid == fileId && info.owneruid == credentials.userId && 'U' in info.permissions) { "Unverified cloud file" }
        val segments = info.filename.removePrefix("/").split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it && '\u0000' !in it })
        require(segments.last() == info.basename && info.etag.isNotBlank())
        val url = fileUrl(credentials.serverUrl, credentials.userId, info.filename)
        // Verify the id at the exact DAV path immediately before writing. ETag also guards path replacement.
        val find = transport.authenticatedRequestBuilder(credentials, url.toString(), "application/xml")
            .method("PROPFIND", """<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:fileid/></d:prop></d:propfind>""".xmlBody())
            .header("Depth", "0").build()
        client.newCall(find).await().use {
            if (it.code != 207 || davProperty(it.body.string(), url, "fileid") != fileId.toString()) {
                throw IOException("Cloud file identity could not be verified")
            }
        }
        val body = """<d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:set><d:prop><oc:favorite>${if (favorite) 1 else 0}</oc:favorite></d:prop></d:set></d:propertyupdate>"""
        val request = transport.authenticatedRequestBuilder(credentials, url.toString(), "application/xml")
            .method("PROPPATCH", body.xmlBody()).header("If-Match", "\"${info.etag.trim('"')}\"").build()
        client.newCall(request).await().use {
            if (it.code != 207 || davProperty(it.body.string(), url, "favorite") == null) {
                throw IOException("Cloud favorite was not confirmed (HTTP ${it.code})")
            }
        }
    }
}

private fun String.xmlBody() = toRequestBody("application/xml; charset=utf-8".toMediaType())

/** A successful multistatus must name the exact resource and the requested property in a 2xx propstat. */
internal fun davProperty(xml: String, expected: HttpUrl, property: String): String? {
    require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
    val doc = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> throw IOException("External XML entities are forbidden") }
    }.parse(InputSource(StringReader(xml)))
    require(doc.documentElement.localName == "multistatus" && doc.documentElement.namespaceURI == "DAV:")
    val responses = doc.documentElement.getElementsByTagNameNS("DAV:", "response")
    if (responses.length != 1) return null
    val response = responses.item(0) as Element
    val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: return null
    val actual = expected.resolve(href) ?: return null
    if (actual.scheme != expected.scheme || actual.host != expected.host || actual.port != expected.port ||
        actual.pathSegments != expected.pathSegments || actual.query != null || actual.fragment != null) return null
    val stats = response.getElementsByTagNameNS("DAV:", "propstat")
    var result: String? = null
    for (i in 0 until stats.length) {
        val stat = stats.item(i) as Element
        val props = stat.getElementsByTagNameNS("DAV:", "prop").item(0) as? Element ?: continue
        val matches = props.getElementsByTagNameNS("http://owncloud.org/ns", property)
        if (matches.length == 0) continue
        val code = stat.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent
            ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
        if (matches.length != 1 || result != null || code !in 200..299) return null
        result = matches.item(0).textContent
    }
    return result
}

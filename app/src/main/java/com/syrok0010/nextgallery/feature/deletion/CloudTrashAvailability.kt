package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.await
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Only an explicit JSON true in a successful OCS response promises a cloud trash bin. */
internal fun cloudTrashAvailable(body: String): Boolean? {
    val root = runCatching {
        Json.parseToJsonElement(
            body,
        )
    }.getOrNull() as? JsonObject ?: return null
    val ocs = root["ocs"] as? JsonObject ?: return null
    val meta = ocs["meta"] as? JsonObject ?: return null
    val status = (meta["statuscode"] as? JsonPrimitive)?.intOrNull
    if (status != 100 && status != 200) return null
    val data = ocs["data"] as? JsonObject ?: return null
    val capabilities = data["capabilities"] as? JsonObject ?: return null
    val files = capabilities["files"] as? JsonObject ?: return null
    val undelete = files["undelete"] as? JsonPrimitive ?: return null
    return undelete.takeUnless { it.isString }?.booleanOrNull
}

internal class NextcloudTrashBin(private val transport: NextcloudTransport) {
    suspend fun available(credentials: AccountCredentials): Boolean? {
        val request = transport
            .authenticatedRequestBuilder(
                credentials,
                transport.normalizeBaseUrl(credentials.serverUrl) +
                    "ocs/v2.php/cloud/capabilities?format=json",
            ).build()
        return transport.baseClient.newCall(request).await().use { response ->
            if (response.isSuccessful) cloudTrashAvailable(response.body.string()) else null
        }
    }
}

/** Session-owned, memory-only capabilities; a failed refresh must not retain an old promise. */
internal class CloudTrashAvailability(
    sessions: SessionStore,
    scope: CoroutineScope,
    load: suspend (AccountCredentials) -> Boolean?,
) {
    private val mutableAvailable = MutableStateFlow<Boolean?>(null)
    val available = mutableAvailable.asStateFlow()
    private val refreshes = MutableStateFlow(0)

    init {
        scope.launch {
            sessions.session.collectLatest { session ->
                mutableAvailable.value = null
                if (session !is SessionUiState.SignedIn) return@collectLatest
                refreshes.collectLatest {
                    mutableAvailable.value = null
                    mutableAvailable.value = try {
                        load(session.credentials)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
    }

    fun refresh() {
        mutableAvailable.value = null
        refreshes.update { it + 1 }
    }
}

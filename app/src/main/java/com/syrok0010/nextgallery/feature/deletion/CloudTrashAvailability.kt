package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.core.network.capabilities.NextcloudCapabilitiesClient
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

/**
 * Only an explicit `files.undelete` promises a cloud trash bin; an absent field stays unknown.
 * Request and format errors propagate to [CloudTrashAvailability], which drops the promise.
 */
internal class NextcloudTrashBin(private val client: NextcloudCapabilitiesClient) {
    suspend fun available(credentials: AccountCredentials): Boolean? =
        client.capabilities(credentials).files?.undelete
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

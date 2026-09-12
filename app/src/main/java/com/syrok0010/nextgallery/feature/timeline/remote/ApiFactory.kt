package com.syrok0010.nextgallery.feature.timeline.remote

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials

internal fun NextcloudTransport.memoriesApi(credentials: AccountCredentials): MemoriesApi =
    retrofit(normalizeBaseUrl(credentials.serverUrl), authenticatedClient(credentials)).create(MemoriesApi::class.java)

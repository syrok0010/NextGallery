package com.syrok0010.nextgallery.feature.auth

import com.syrok0010.nextgallery.core.network.NextcloudTransport

internal fun NextcloudTransport.nextcloudAuthApi(serverUrl: String): NextcloudAuthApi =
    retrofit(normalizeBaseUrl(serverUrl), baseClient).create(NextcloudAuthApi::class.java)

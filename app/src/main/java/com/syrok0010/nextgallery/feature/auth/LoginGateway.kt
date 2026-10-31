package com.syrok0010.nextgallery.feature.auth

import com.syrok0010.nextgallery.core.session.AccountCredentials

interface LoginGateway {
    suspend fun checkMemories(credentials: AccountCredentials): MemoriesCompatibility
    suspend fun startLogin(serverUrl: String): LoginSession
    suspend fun pollLogin(session: LoginSession): LoginPollResult
}

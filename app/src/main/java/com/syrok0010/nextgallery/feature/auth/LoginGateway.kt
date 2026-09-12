package com.syrok0010.nextgallery.feature.auth

interface LoginGateway {
    suspend fun startLogin(serverUrl: String): LoginSession
    suspend fun pollLogin(session: LoginSession): LoginPollResult
}

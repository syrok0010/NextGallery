package com.syrok0010.nextgallery.data.credentials

interface CredentialsStore {
    fun load(): AccountCredentials?
    fun save(credentials: AccountCredentials)
    fun clear()
}

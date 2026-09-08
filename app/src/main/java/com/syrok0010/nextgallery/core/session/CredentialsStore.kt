package com.syrok0010.nextgallery.core.session

interface CredentialsStore {
    fun load(): AccountCredentials?
    fun save(credentials: AccountCredentials)
    fun clear()
}

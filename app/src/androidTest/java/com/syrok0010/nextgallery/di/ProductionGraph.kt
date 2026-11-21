package com.syrok0010.nextgallery.di

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** Production definitions with private disk storage and an explicit credential-store replacement. */
internal class ProductionGraph(credentials: AccountCredentials? = null) : AutoCloseable {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(base.cacheDir, "graph-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(directory, name)
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
    }
    val application = koinApplication {
        androidContext(context)
        allowOverride(true)
        modules(
            appModule(),
            module {
                single<CredentialsStore> {
                    object : CredentialsStore {
                        override fun load() = credentials
                        override fun save(credentials: AccountCredentials) = Unit
                    }
                }
            },
        )
    }
    val koin get() = application.koin
    init {
        koin.get<SessionStore>()
    }
    val scope get() = koin.get<AuthenticatedGraph>().requireScope()

    @Composable fun Content(content: @Composable () -> Unit) {
        KoinIsolatedContext(application, content = content)
    }

    override fun close() {
        // Stop graph-owned jobs before closing root databases.
        koin.get<AuthenticatedGraph>().close()
        application.close()
        directory.deleteRecursively()
    }
}

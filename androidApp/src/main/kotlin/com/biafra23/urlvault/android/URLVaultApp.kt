package com.biafra23.urlvault.android

import android.app.Application
import com.biafra23.urlvault.android.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Application class that initialises Koin DI on startup.
 */
class URLVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        val isDebug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        startKoin {
            androidLogger(if (isDebug) Level.INFO else Level.ERROR)
            androidContext(this@URLVaultApp)
            modules(appModule)
        }
    }
}

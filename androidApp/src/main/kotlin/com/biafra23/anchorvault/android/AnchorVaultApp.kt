package com.biafra23.anchorvault.android

import android.app.Application
import com.biafra23.anchorvault.android.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Application class that initialises Koin DI on startup.
 */
class AnchorVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.INFO)
            androidContext(this@AnchorVaultApp)
            modules(appModule)
        }
    }
}

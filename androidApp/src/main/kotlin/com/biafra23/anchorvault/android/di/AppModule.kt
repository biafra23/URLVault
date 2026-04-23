package com.biafra23.anchorvault.android.di

import android.content.Context
import com.biafra23.anchorvault.android.ai.AICoreService
import com.biafra23.anchorvault.android.database.AppDatabase
import com.biafra23.anchorvault.android.database.DatabaseKeyManager
import com.biafra23.anchorvault.android.database.RoomBookmarkRepository
import com.biafra23.anchorvault.android.sync.AndroidBitwardenPreferences
import com.biafra23.anchorvault.autotag.AutoTagService
import com.biafra23.anchorvault.repository.BookmarkRepository
import com.biafra23.anchorvault.sync.BitwardenSyncService
import com.biafra23.anchorvault.sync.KtorBitwardenSyncService
import com.biafra23.anchorvault.viewmodel.BookmarkViewModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    // Shared HttpClient
    single {
        HttpClient {
            install(HttpTimeout) { requestTimeoutMillis = 10_000 }
            followRedirects = true
        }
    }

    // Application-level coroutine scope
    single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    // Bitwarden credential storage
    single { AndroidBitwardenPreferences(androidContext()) }

    // Database
    single<AppDatabase> {
        val context: Context = androidContext()
        val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)
        AppDatabase.getInstance(context, passphrase)
    }

    single { get<AppDatabase>().bookmarkDao() }

    // Repository
    single<BookmarkRepository> {
        RoomBookmarkRepository(get())
    }

    // Bitwarden sync
    single<BitwardenSyncService> {
        val service = KtorBitwardenSyncService(get())
        // Restore previously saved credentials on app start
        val prefs = get<AndroidBitwardenPreferences>()
        val savedCredentials = prefs.loadCredentials()
        if (savedCredentials != null) {
            get<CoroutineScope>().launch {
                service.configure(savedCredentials)
            }
        }
        service
    }

    // Auto-tag service
    single { AutoTagService(get()) }

    // ML Kit GenAI Prompt API (Gemini Nano on-device)
    single { AICoreService(get()) }

    // ViewModel
    viewModel {
        val aiCoreService = get<AICoreService>()
        BookmarkViewModel(
            repository = get(),
            syncService = get(),
            autoTagService = get(),
            aiTagGenerator = { url, title, desc -> aiCoreService.generateTags(url, title, desc) },
            aiDescriptionGenerator = { url, title -> aiCoreService.generateDescription(url, title) },
            aiTitleGenerator = { url -> aiCoreService.generateTitle(url) }
        )
    }
}

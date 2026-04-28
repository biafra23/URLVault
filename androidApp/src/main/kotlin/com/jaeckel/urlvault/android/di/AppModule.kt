package com.jaeckel.urlvault.android.di

import android.content.Context
import com.jaeckel.urlvault.ai.LocalModelRegistry
import com.jaeckel.urlvault.ai.ModelCatalog
import com.jaeckel.urlvault.ai.ModelComparisonRunner
import com.jaeckel.urlvault.android.ai.AICoreService
import com.jaeckel.urlvault.android.ai.AICoreServiceAdapter
import com.jaeckel.urlvault.android.ai.LeapNativeBridge
import com.jaeckel.urlvault.android.ai.LeapSdkNativeBridge
import com.jaeckel.urlvault.android.ai.LlamaCppNativeBridge
import com.jaeckel.urlvault.android.ai.LlamatikNativeBridge
import com.jaeckel.urlvault.android.ai.LocalModelPreferences
import com.jaeckel.urlvault.android.ai.LocalModelRouter
import com.jaeckel.urlvault.android.ai.ModelDownloadManager
import com.jaeckel.urlvault.android.database.AppDatabase
import com.jaeckel.urlvault.android.database.DatabaseKeyManager
import com.jaeckel.urlvault.android.database.RoomBookmarkRepository
import com.jaeckel.urlvault.android.sync.AndroidBitwardenPreferences
import com.jaeckel.urlvault.autotag.AutoTagService
import com.jaeckel.urlvault.repository.BookmarkRepository
import com.jaeckel.urlvault.sync.BitwardenSyncService
import com.jaeckel.urlvault.sync.KtorBitwardenSyncService
import com.jaeckel.urlvault.viewmodel.BookmarkViewModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
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
            // Required by KtorBitwardenSyncService — `setBody(mapOf(...))` and
            // similar in shared/sync/* won't serialize without this.
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    // Don't write defaults or nulls — Vaultwarden's typed-
                    // cipher DTO validator rejects bodies that emit
                    // {"login": null, "card": null, ...} for an unused field
                    // type with a 422 Unprocessable Entity.
                    encodeDefaults = false
                    explicitNulls = false
                })
            }
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

    // --- Local model framework -------------------------------------------------

    // Persisted model selection / custom catalog entries
    single { LocalModelPreferences(androidContext()) }

    // Live registry of installed local-model providers (Gemini Nano + GGUFs)
    single {
        val registry = LocalModelRegistry()
        // Wrap the existing AICoreService as a provider so it shows up alongside
        // any downloaded GGUFs in the comparison UI and the bookmark AI router.
        registry.register(AICoreServiceAdapter(get()))
        registry
    }

    // llama.cpp via Llamatik's prebuilt JNI .so files. ABI is filtered to
    // arm64-v8a in androidApp/build.gradle.kts to keep APK size in check.
    single<LlamaCppNativeBridge> { LlamatikNativeBridge() }

    // LeapSDK runtime — runs LFM2-family `.bundle` models with structured JSON
    // output (see `LeapModelProvider`). Falls back to NoOp via the no-op
    // pattern if the SDK class fails to load on this device.
    single<LeapNativeBridge> { LeapSdkNativeBridge() }

    // Cross-provider comparison helper (used by both DEBUG logcat benchmark
    // and the user-visible ModelComparisonScreen)
    single { ModelComparisonRunner(get()) }

    // Hugging Face downloader; rehydrates already-downloaded GGUFs at startup
    single {
        val mgr = ModelDownloadManager(
            context = androidContext(),
            sharedHttp = get(),
            registry = get(),
            bridge = get(),
            leapBridge = get(),
            appScope = get(),
            authTokenProvider = { get<LocalModelPreferences>().loadHfToken() },
        )
        // Combine built-in + user-added entries and rehydrate from disk.
        val customEntries = get<LocalModelPreferences>().loadCustomEntries()
        mgr.rehydrateFromDisk(ModelCatalog.builtIn + customEntries)
        // Also wire the comparison runner into AICoreService so the DEBUG
        // benchmark covers all installed models, not just Gemini Nano.
        get<AICoreService>().comparisonRunner = get()
        mgr
    }

    // Routes bookmark AI calls to the user's selected provider.
    single {
        LocalModelRouter(
            registry = get(),
            activeIdsProvider = { get<LocalModelPreferences>().loadActiveIds() },
        )
    }

    // ViewModel
    viewModel {
        // Eagerly create the download manager so rehydration happens at startup.
        get<ModelDownloadManager>()
        val router = get<LocalModelRouter>()
        BookmarkViewModel(
            repository = get(),
            syncService = get(),
            autoTagService = get(),
            aiTagGenerator = { url, title, desc -> router.generateTags(url, title, desc) },
            aiDescriptionGenerator = { url, title -> router.generateDescription(url, title) },
            aiTitleGenerator = { url -> router.generateTitle(url) }
        )
    }
}

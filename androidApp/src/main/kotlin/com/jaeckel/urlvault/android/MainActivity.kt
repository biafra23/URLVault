package com.jaeckel.urlvault.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jaeckel.urlvault.ai.AiProviderIds
import com.jaeckel.urlvault.ai.ModelCatalog
import com.jaeckel.urlvault.ai.ModelCatalogEntry
import com.jaeckel.urlvault.ai.ModelComparisonRunner
import com.jaeckel.urlvault.ai.ModelRuntime
import com.jaeckel.urlvault.android.ai.AICoreService
import com.jaeckel.urlvault.android.ai.AICoreStatus
import com.jaeckel.urlvault.android.ai.LocalModelPreferences
import com.jaeckel.urlvault.android.ai.LocalModelRouter
import com.jaeckel.urlvault.android.ai.ModelDownloadManager
import com.jaeckel.urlvault.android.sync.AndroidBitwardenPreferences
import com.jaeckel.urlvault.model.Bookmark
import com.jaeckel.urlvault.sync.BitwardenSyncService
import com.jaeckel.urlvault.ui.AddEditBookmarkScreen
import com.jaeckel.urlvault.ui.BookmarkListScreen
import com.jaeckel.urlvault.ui.ModelComparisonScreen
import com.jaeckel.urlvault.ui.SettingsScreen
import com.jaeckel.urlvault.ui.theme.URLVaultTheme
import com.jaeckel.urlvault.viewmodel.BookmarkViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Main (and only) Activity. Navigation is handled via in-memory screen state
 * using Compose to keep the setup minimal while still supporting all required screens.
 */
class MainActivity : ComponentActivity() {

    private val bookmarkViewModel: BookmarkViewModel by viewModel()
    private val bitwardenPrefs: AndroidBitwardenPreferences by inject()
    private val syncService: BitwardenSyncService by inject()
    private val aiCoreService: AICoreService by inject()
    private val localModelPrefs: LocalModelPreferences by inject()
    private val modelDownloadManager: ModelDownloadManager by inject()
    private val modelComparisonRunner: ModelComparisonRunner by inject()
    private val localModelRouter: LocalModelRouter by inject()
    // Background scope from DI (Dispatchers.IO + SupervisorJob) — survives
    // Activity recreation, so a model warm-up triggered by an activation
    // toggle keeps running even if the user rotates the screen mid-load.
    private val appScope: CoroutineScope by inject()

    /** Hoisted so onNewIntent can update navigation state. */
    private var currentScreen by mutableStateOf<Screen>(Screen.List)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Pre-populate URL from an incoming SEND intent (share from browser)
        handleShareIntent(intent)

        setContent {
            URLVaultTheme {
                // Without `remember`, mutableStateOf is rebuilt every
                // recomposition and re-hits EncryptedSharedPreferences
                // (Keystore-backed AES). When a download flow is ticking
                // that's tens of decrypts per second — a real ANR source.
                var autoTagEnabled by remember { mutableStateOf(bitwardenPrefs.loadAutoTagEnabled()) }
                var aiCoreEnabled by remember { mutableStateOf(bitwardenPrefs.loadAiCoreEnabled()) }
                val aiCoreStatus by aiCoreService.status.collectAsState()
                val downloadStates by modelDownloadManager.states.collectAsState()
                val warmingIds by localModelRouter.warmingIds.collectAsState()
                var customEntries by remember { mutableStateOf(localModelPrefs.loadCustomEntries()) }
                var activeIds by remember { mutableStateOf(localModelPrefs.loadActiveIds()) }
                // Settings reads two heavy values from EncryptedSharedPreferences:
                // the Bitwarden credentials (decrypts via Keystore) and the
                // field-history blob. Cache them in remembered state and only
                // refresh when the user actually saves new credentials, so
                // recomposition (e.g. for download-progress ticks) doesn't
                // cost a Keystore round-trip every frame.
                var savedCredentials by remember { mutableStateOf(bitwardenPrefs.loadCredentials()) }
                var fieldHistory by remember { mutableStateOf(bitwardenPrefs.loadFieldHistory()) }

                // Kick off AICore initialization once
                LaunchedEffect(Unit) {
                    aiCoreService.initialize()
                }

                // DEBUG-only: surface which provider actually served each AI call
                // so we can confirm an "activated" model is what's being used vs.
                // silently falling back to AICore.
                if (BuildConfig.DEBUG) {
                    LaunchedEffect(Unit) {
                        localModelRouter.events.collect { event ->
                            val readinessLine = event.readiness.joinToString { (id, r) ->
                                "${id.substringAfter(':')}=${if (r) "✓" else "✗"}"
                            }
                            val activeLine = if (event.activeIds.isEmpty()) "active=none"
                                else "active=${event.activeIds.joinToString { it.substringAfter(':') }}"
                            val head = when (event) {
                                is LocalModelRouter.RouteEvent.Picked ->
                                    "AI ${event.action}: ${event.providerName}\n${event.reason}"
                                is LocalModelRouter.RouteEvent.None ->
                                    "AI ${event.action}: NO PROVIDER\n${event.reason}"
                            }
                            val text = "$head\n$activeLine\n$readinessLine"
                            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                // Show toggle for any status except Unknown (still probing)
                val aiCoreAvailable = aiCoreStatus !is AICoreStatus.Unknown && aiCoreStatus !is AICoreStatus.Unavailable
                val aiCoreStatusText = when (aiCoreStatus) {
                    is AICoreStatus.Downloading -> "Gemini Nano model is downloading..."
                    is AICoreStatus.Available -> "On-device AI is ready."
                    is AICoreStatus.Failed -> "AI model error: ${(aiCoreStatus as AICoreStatus.Failed).message}"
                    else -> null
                }

                // True when AICore OR any downloaded local model is ready to serve a request.
                // Re-polled whenever AICore status, downloads, or the active-IDs set changes,
                // so the AI path turns on as soon as any provider becomes usable.
                val anyProviderReady by produceState(
                    initialValue = aiCoreStatus is AICoreStatus.Available,
                    aiCoreStatus, downloadStates, activeIds,
                ) {
                    value = localModelRouter.hasReadyProvider()
                }

                when (val screen = currentScreen) {
                    is Screen.List -> BookmarkListScreen(
                        viewModel = bookmarkViewModel,
                        onAddBookmark = { currentScreen = Screen.AddEdit() },
                        onEditBookmark = { bookmark -> currentScreen = Screen.AddEdit(existing = bookmark) },
                        onOpenSettings = { currentScreen = Screen.Settings }
                    )

                    is Screen.AddEdit -> {
                        val uiState = bookmarkViewModel.uiState.value
                        val autoTagState by bookmarkViewModel.autoTagState.collectAsState()
                        val aiTagState by bookmarkViewModel.aiTagState.collectAsState()
                        val aiDescriptionState by bookmarkViewModel.aiDescriptionState.collectAsState()
                        val aiTitleState by bookmarkViewModel.aiTitleState.collectAsState()
                        AddEditBookmarkScreen(
                            existingBookmark = screen.existing,
                            prefilledUrl = screen.prefilledUrl,
                            autoTagEnabled = autoTagEnabled,
                            autoTagState = autoTagState,
                            onAutoTag = { url -> bookmarkViewModel.fetchAutoTags(url) },
                            onAutoTagConsumed = { bookmarkViewModel.clearAutoTagState() },
                            aiCoreEnabled = aiCoreEnabled && anyProviderReady,
                            aiTagState = aiTagState,
                            aiDescriptionState = aiDescriptionState,
                            aiTitleState = aiTitleState,
                            onAiGenerateTags = { url, title, desc -> bookmarkViewModel.generateAiTags(url, title, desc) },
                            onAiGenerateDescription = { url, title -> bookmarkViewModel.generateAiDescription(url, title) },
                            onAiGenerateTitle = { url -> bookmarkViewModel.generateAiTitle(url) },
                            onAiTagConsumed = { bookmarkViewModel.clearAiTagState() },
                            onAiDescriptionConsumed = { bookmarkViewModel.clearAiDescriptionState() },
                            onAiTitleConsumed = { bookmarkViewModel.clearAiTitleState() },
                            onSave = { bookmark ->
                                if (screen.existing != null) {
                                    bookmarkViewModel.updateBookmark(bookmark)
                                } else {
                                    bookmarkViewModel.addBookmark(bookmark)
                                }
                                currentScreen = Screen.List
                            },
                            onCancel = { currentScreen = Screen.List }
                        )
                    }

                    is Screen.Settings -> {
                        val catalog = ModelCatalog.builtIn + customEntries
                        SettingsScreen(
                            currentCredentials = savedCredentials,
                            syncService = syncService,
                            autoTagEnabled = autoTagEnabled,
                            onAutoTagEnabledChanged = { enabled ->
                                autoTagEnabled = enabled
                                bitwardenPrefs.saveAutoTagEnabled(enabled)
                            },
                            aiCoreAvailable = aiCoreAvailable,
                            aiCoreEnabled = aiCoreEnabled,
                            aiCoreStatusText = aiCoreStatusText,
                            onAiCoreEnabledChanged = { enabled ->
                                aiCoreEnabled = enabled
                                bitwardenPrefs.saveAiCoreEnabled(enabled)
                            },
                            onToggleAiCoreActive = { active ->
                                // Same radio-button semantics as the Llama toggles —
                                // selecting a provider clears any others.
                                activeIds = if (active) setOf(AiProviderIds.AICORE)
                                    else activeIds - AiProviderIds.AICORE
                                localModelPrefs.saveActiveIds(activeIds)
                                android.util.Log.i(
                                    "MainActivity",
                                    "onToggleAiCoreActive: active=$active -> activeIds=$activeIds",
                                )
                                if (active) appScope.launch { localModelRouter.warmUpActive() }
                            },
                            localModelCatalog = catalog,
                            localModelStates = downloadStates,
                            activeModelIds = activeIds,
                            warmingModelIds = warmingIds,
                            onDownloadModel = { entry -> modelDownloadManager.download(entry) },
                            onCancelModelDownload = { entry -> modelDownloadManager.cancel(entry) },
                            onDeleteModel = { entry ->
                                modelDownloadManager.delete(entry)
                                if (entry.id in activeIds) {
                                    activeIds = activeIds - entry.id
                                    localModelPrefs.saveActiveIds(activeIds)
                                }
                            },
                            onToggleModelActive = { entry, active ->
                                // Llamatik's LlamaBridge is a singleton — only one model
                                // can be loaded at a time. Activating one clears the rest
                                // so the toggle behaves like a radio button.
                                activeIds = if (active) setOf(entry.id) else activeIds - entry.id
                                localModelPrefs.saveActiveIds(activeIds)
                                android.util.Log.i(
                                    "MainActivity",
                                    "onToggleModelActive: id=${entry.id} active=$active -> activeIds=$activeIds",
                                )
                                // Pre-warm the newly-active model so the first
                                // generate() call doesn't pay model-load cost.
                                if (active) appScope.launch { localModelRouter.warmUpActive() }
                            },
                            onAddCustomModel = { hfRepo, hfFile, displayName ->
                                val newEntry = ModelCatalogEntry(
                                    id = "custom:" + hfRepo.lowercase().replace('/', '_') + ":" + hfFile.lowercase(),
                                    displayName = displayName,
                                    runtime = ModelRuntime.LLAMA_CPP,
                                    hfRepo = hfRepo,
                                    hfFile = hfFile,
                                    approxBytes = 0L,
                                    license = "Unknown",
                                    builtIn = false,
                                )
                                customEntries = (customEntries + newEntry).distinctBy { it.id }
                                localModelPrefs.saveCustomEntries(customEntries)
                            },
                            onOpenComparison = { currentScreen = Screen.Comparison },
                            onSaveCredentials = { credentials ->
                                bitwardenPrefs.saveCredentials(credentials)
                                bitwardenPrefs.addToFieldHistory(credentials)
                                savedCredentials = credentials
                                fieldHistory = bitwardenPrefs.loadFieldHistory()
                                bookmarkViewModel.configureBitwarden(credentials)
                            },
                            onNavigateBack = { currentScreen = Screen.List },
                            fieldHistory = fieldHistory
                        )
                    }

                    is Screen.Comparison -> {
                        ModelComparisonScreen(
                            runner = modelComparisonRunner,
                            onNavigateBack = { currentScreen = Screen.Settings },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { url ->
                currentScreen = Screen.AddEdit(prefilledUrl = url)
            }
        }
    }
}

/** Simple sealed class for in-Compose navigation without a navigation library. */
sealed class Screen {
    data object List : Screen()
    data class AddEdit(
        val existing: Bookmark? = null,
        val prefilledUrl: String? = null
    ) : Screen()
    data object Settings : Screen()
    data object Comparison : Screen()
}

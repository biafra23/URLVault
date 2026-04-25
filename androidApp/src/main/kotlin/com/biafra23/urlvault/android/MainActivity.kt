package com.biafra23.anchorvault.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.biafra23.anchorvault.android.ai.AICoreService
import com.biafra23.anchorvault.android.ai.AICoreStatus
import com.biafra23.anchorvault.android.sync.AndroidBitwardenPreferences
import com.biafra23.anchorvault.model.Bookmark
import com.biafra23.anchorvault.sync.BitwardenSyncService
import com.biafra23.anchorvault.ui.AddEditBookmarkScreen
import com.biafra23.anchorvault.ui.BookmarkListScreen
import com.biafra23.anchorvault.ui.SettingsScreen
import com.biafra23.anchorvault.ui.theme.AnchorVaultTheme
import com.biafra23.anchorvault.viewmodel.BookmarkViewModel
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

    /** Hoisted so onNewIntent can update navigation state. */
    private var currentScreen by mutableStateOf<Screen>(Screen.List)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Pre-populate URL from an incoming SEND intent (share from browser)
        handleShareIntent(intent)

        setContent {
            AnchorVaultTheme {
                var autoTagEnabled by mutableStateOf(bitwardenPrefs.loadAutoTagEnabled())
                var aiCoreEnabled by mutableStateOf(bitwardenPrefs.loadAiCoreEnabled())
                val aiCoreStatus by aiCoreService.status.collectAsState()

                // Kick off AICore initialization once
                LaunchedEffect(Unit) {
                    aiCoreService.initialize()
                }

                // Show toggle for any status except Unknown (still probing)
                val aiCoreAvailable = aiCoreStatus !is AICoreStatus.Unknown && aiCoreStatus !is AICoreStatus.Unavailable
                val aiCoreStatusText = when (aiCoreStatus) {
                    is AICoreStatus.Downloading -> "Gemini Nano model is downloading..."
                    is AICoreStatus.Available -> "On-device AI is ready."
                    is AICoreStatus.Failed -> "AI model error: ${(aiCoreStatus as AICoreStatus.Failed).message}"
                    else -> null
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
                            aiCoreEnabled = aiCoreEnabled && aiCoreStatus is AICoreStatus.Available,
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
                        SettingsScreen(
                            currentCredentials = bitwardenPrefs.loadCredentials(),
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
                            onSaveCredentials = { credentials ->
                                bitwardenPrefs.saveCredentials(credentials)
                                bitwardenPrefs.addToFieldHistory(credentials)
                                bookmarkViewModel.configureBitwarden(credentials)
                            },
                            onNavigateBack = { currentScreen = Screen.List },
                            fieldHistory = bitwardenPrefs.loadFieldHistory()
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
}

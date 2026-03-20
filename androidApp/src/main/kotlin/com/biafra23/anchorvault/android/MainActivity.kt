package com.biafra23.anchorvault.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.biafra23.anchorvault.android.sync.AndroidBitwardenPreferences
import com.biafra23.anchorvault.model.Bookmark
import com.biafra23.anchorvault.sync.BitwardenCredentials
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

    /** Hoisted so onNewIntent can update navigation state. */
    private var currentScreen by mutableStateOf<Screen>(Screen.List)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Pre-populate URL from an incoming SEND intent (share from browser)
        handleShareIntent(intent)

        setContent {
            AnchorVaultTheme {
                var autoTagEnabled by remember { mutableStateOf(bitwardenPrefs.loadAutoTagEnabled()) }

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
                        AddEditBookmarkScreen(
                            existingBookmark = screen.existing,
                            prefilledUrl = screen.prefilledUrl,
                            existingTags = uiState.allTags,
                            autoTagEnabled = autoTagEnabled,
                            autoTagState = autoTagState,
                            onAutoTag = { url -> bookmarkViewModel.fetchAutoTags(url) },
                            onAutoTagConsumed = { bookmarkViewModel.clearAutoTagState() },
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
                            autoTagEnabled = autoTagEnabled,
                            onAutoTagEnabledChanged = { enabled ->
                                autoTagEnabled = enabled
                                bitwardenPrefs.saveAutoTagEnabled(enabled)
                            },
                            onSaveCredentials = { credentials: BitwardenCredentials ->
                                bitwardenPrefs.saveCredentials(credentials)
                                bookmarkViewModel.configureBitwarden(credentials)
                                currentScreen = Screen.List
                            },
                            onNavigateBack = { currentScreen = Screen.List }
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

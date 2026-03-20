package com.biafra23.anchorvault.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.biafra23.anchorvault.autotag.createAutoTagService
import com.biafra23.anchorvault.model.Bookmark
import com.biafra23.anchorvault.sync.BitwardenCredentials
import com.biafra23.anchorvault.sync.createBitwardenSyncService
import com.biafra23.anchorvault.ui.AddEditBookmarkScreen
import com.biafra23.anchorvault.ui.BookmarkListScreen
import com.biafra23.anchorvault.ui.SettingsScreen
import com.biafra23.anchorvault.ui.theme.AnchorVaultTheme
import com.biafra23.anchorvault.viewmodel.BookmarkViewModel
import kotlinx.coroutines.runBlocking

/**
 * Desktop application entry point.
 */
fun main() = application {
    val prefs = remember { DesktopBitwardenPreferences() }
    val repository = remember { DesktopBookmarkRepository() }
    val syncService = remember {
        createBitwardenSyncService().also { service ->
            val saved = prefs.loadCredentials()
            if (saved != null) {
                runBlocking { service.configure(saved) }
            }
        }
    }
    val autoTagService = remember { createAutoTagService() }
    val viewModel = remember { BookmarkViewModel(repository, syncService, autoTagService) }

    Window(
        onCloseRequest = {
            repository.close()
            syncService.close()
            autoTagService.close()
            exitApplication()
        },
        title = "AnchorVault",
        state = rememberWindowState(width = 1000.dp, height = 700.dp)
    ) {
        AnchorVaultTheme {
            var currentScreen by remember { mutableStateOf<DesktopScreen>(DesktopScreen.List) }
            var autoTagEnabled by remember { mutableStateOf(prefs.loadAutoTagEnabled()) }

            when (val screen = currentScreen) {
                is DesktopScreen.List -> BookmarkListScreen(
                    viewModel = viewModel,
                    onAddBookmark = { currentScreen = DesktopScreen.AddEdit() },
                    onEditBookmark = { bookmark -> currentScreen = DesktopScreen.AddEdit(existing = bookmark) },
                    onOpenSettings = { currentScreen = DesktopScreen.Settings }
                )

                is DesktopScreen.AddEdit -> {
                    val uiState = viewModel.uiState.value
                    val autoTagState by viewModel.autoTagState.collectAsState()
                    AddEditBookmarkScreen(
                        existingBookmark = screen.existing,
                        existingTags = uiState.allTags,
                        autoTagEnabled = autoTagEnabled,
                        autoTagState = autoTagState,
                        onAutoTag = { url -> viewModel.fetchAutoTags(url) },
                        onAutoTagConsumed = { viewModel.clearAutoTagState() },
                        onSave = { bookmark ->
                            if (screen.existing != null) {
                                viewModel.updateBookmark(bookmark)
                            } else {
                                viewModel.addBookmark(bookmark)
                            }
                            currentScreen = DesktopScreen.List
                        },
                        onCancel = { currentScreen = DesktopScreen.List }
                    )
                }

                is DesktopScreen.Settings -> {
                    SettingsScreen(
                        currentCredentials = prefs.loadCredentials(),
                        autoTagEnabled = autoTagEnabled,
                        onAutoTagEnabledChanged = { enabled ->
                            autoTagEnabled = enabled
                            prefs.saveAutoTagEnabled(enabled)
                        },
                        onSaveCredentials = { credentials: BitwardenCredentials ->
                            prefs.saveCredentials(credentials)
                            viewModel.configureBitwarden(credentials)
                            currentScreen = DesktopScreen.List
                        },
                        onNavigateBack = { currentScreen = DesktopScreen.List }
                    )
                }
            }
        }
    }
}

sealed class DesktopScreen {
    data object List : DesktopScreen()
    data class AddEdit(val existing: Bookmark? = null) : DesktopScreen()
    data object Settings : DesktopScreen()
}

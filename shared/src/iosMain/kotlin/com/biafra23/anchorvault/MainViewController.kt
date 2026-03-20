package com.biafra23.anchorvault

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.biafra23.anchorvault.autotag.createAutoTagService
import com.biafra23.anchorvault.database.IosBookmarkRepository
import com.biafra23.anchorvault.model.Bookmark
import com.biafra23.anchorvault.sync.createBitwardenSyncService
import com.biafra23.anchorvault.ui.AddEditBookmarkScreen
import com.biafra23.anchorvault.ui.BookmarkListScreen
import com.biafra23.anchorvault.ui.SettingsScreen
import com.biafra23.anchorvault.ui.theme.AnchorVaultTheme
import com.biafra23.anchorvault.viewmodel.BookmarkViewModel
import platform.UIKit.UIViewController

@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController {
    val repository = remember { IosBookmarkRepository() }
    val syncService = remember { createBitwardenSyncService() }
    val autoTagService = remember { createAutoTagService() }
    val viewModel = remember { BookmarkViewModel(repository, syncService, autoTagService) }

    AnchorVaultTheme {
        var currentScreen by remember { mutableStateOf<IosScreen>(IosScreen.List) }
        var autoTagEnabled by remember { mutableStateOf(false) }

        when (val screen = currentScreen) {
            is IosScreen.List -> BookmarkListScreen(
                viewModel = viewModel,
                onAddBookmark = { currentScreen = IosScreen.AddEdit() },
                onEditBookmark = { bookmark -> currentScreen = IosScreen.AddEdit(existing = bookmark) },
                onOpenSettings = { currentScreen = IosScreen.Settings }
            )

            is IosScreen.AddEdit -> {
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
                        currentScreen = IosScreen.List
                    },
                    onCancel = { currentScreen = IosScreen.List }
                )
            }

            is IosScreen.Settings -> {
                SettingsScreen(
                    currentCredentials = null,
                    syncService = syncService,
                    autoTagEnabled = autoTagEnabled,
                    onAutoTagEnabledChanged = { autoTagEnabled = it },
                    onSaveCredentials = { credentials ->
                        viewModel.configureBitwarden(credentials)
                    },
                    onNavigateBack = { currentScreen = IosScreen.List }
                )
            }
        }
    }
}

sealed class IosScreen {
    data object List : IosScreen()
    data class AddEdit(val existing: Bookmark? = null) : IosScreen()
    data object Settings : IosScreen()
}

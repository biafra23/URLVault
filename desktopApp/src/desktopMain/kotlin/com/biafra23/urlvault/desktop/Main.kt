package com.biafra23.urlvault.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.biafra23.urlvault.autotag.createAutoTagService
import com.biafra23.urlvault.model.Bookmark
import com.biafra23.urlvault.sync.createBitwardenSyncService
import com.biafra23.urlvault.ui.AddEditBookmarkScreen
import com.biafra23.urlvault.ui.BookmarkListScreen
import com.biafra23.urlvault.ui.SettingsScreen
import com.biafra23.urlvault.ui.theme.URLVaultTheme
import com.biafra23.urlvault.viewmodel.BookmarkViewModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking

/**
 * Desktop application entry point.
 */
fun main() = application {
    val httpClient = remember {
        HttpClient {
            install(HttpTimeout) { requestTimeoutMillis = 10_000 }
            followRedirects = true
        }
    }
    val prefs = remember { DesktopBitwardenPreferences() }
    val repository = remember { DesktopBookmarkRepository() }
    val syncService = remember {
        com.biafra23.urlvault.sync.KtorBitwardenSyncService(httpClient).also { service ->
            val saved = prefs.loadCredentials()
            if (saved != null) {
                runBlocking { service.configure(saved) }
            }
        }
    }
    val autoTagService = remember { com.biafra23.urlvault.autotag.AutoTagService(httpClient) }
    val viewModel = remember { BookmarkViewModel(repository, syncService, autoTagService) }

    Window(
        onCloseRequest = {
            repository.close()
            syncService.close()
            autoTagService.close()
            httpClient.close()
            exitApplication()
        },
        title = "URLVault",
        state = rememberWindowState(width = 1000.dp, height = 700.dp)
    ) {
        URLVaultTheme {
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
                        syncService = syncService,
                        autoTagEnabled = autoTagEnabled,
                        onAutoTagEnabledChanged = { enabled ->
                            autoTagEnabled = enabled
                            prefs.saveAutoTagEnabled(enabled)
                        },
                        onSaveCredentials = { credentials ->
                            prefs.saveCredentials(credentials)
                            prefs.addToFieldHistory(credentials)
                            viewModel.configureBitwarden(credentials)
                        },
                        onNavigateBack = { currentScreen = DesktopScreen.List },
                        fieldHistory = prefs.loadFieldHistory()
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

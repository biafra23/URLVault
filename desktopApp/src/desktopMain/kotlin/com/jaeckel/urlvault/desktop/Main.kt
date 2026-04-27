package com.jaeckel.urlvault.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jaeckel.urlvault.autotag.createAutoTagService
import com.jaeckel.urlvault.model.Bookmark
import com.jaeckel.urlvault.sync.createBitwardenSyncService
import com.jaeckel.urlvault.ui.AddEditBookmarkScreen
import com.jaeckel.urlvault.ui.BookmarkListScreen
import com.jaeckel.urlvault.ui.SettingsScreen
import com.jaeckel.urlvault.ui.theme.URLVaultTheme
import com.jaeckel.urlvault.viewmodel.BookmarkViewModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Desktop application entry point.
 */
fun main() = application {
    val httpClient = remember {
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
    val prefs = remember { DesktopBitwardenPreferences() }
    val repository = remember { DesktopBookmarkRepository() }
    val syncService = remember {
        com.jaeckel.urlvault.sync.KtorBitwardenSyncService(httpClient).also { service ->
            val saved = prefs.loadCredentials()
            if (saved != null) {
                // On macOS, the master password lives in a Touch ID-gated
                // Keychain item rather than the encrypted on-disk blob. Reading
                // it triggers the system biometric prompt; we run on
                // Dispatchers.IO so the prompt doesn't block the EDT (which
                // would deadlock the Compose UI before any window appears).
                val savedEmail = saved.email
                val withMaster = if (prefs.isBiometricAvailable() && !savedEmail.isNullOrBlank()) {
                    runBlocking {
                        val pwd = withContext(Dispatchers.IO) {
                            prefs.loadMasterPasswordWithBiometric(savedEmail)
                        }
                        if (pwd.isNullOrBlank()) saved else saved.copy(masterPassword = pwd)
                    }
                } else {
                    saved
                }
                runBlocking { service.configure(withMaster) }
            }
        }
    }
    val autoTagService = remember { com.jaeckel.urlvault.autotag.AutoTagService(httpClient) }
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

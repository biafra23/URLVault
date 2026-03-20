package com.biafra23.anchorvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.biafra23.anchorvault.sync.BitwardenCredentials
import com.biafra23.anchorvault.sync.BitwardenSyncService
import kotlinx.coroutines.launch

/**
 * Settings screen for configuring Bitwarden sync credentials and app preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentCredentials: BitwardenCredentials? = null,
    syncService: BitwardenSyncService,
    autoTagEnabled: Boolean = false,
    onAutoTagEnabledChanged: (Boolean) -> Unit = {},
    onSaveCredentials: (BitwardenCredentials) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var apiBaseUrl by remember { mutableStateOf(currentCredentials?.apiBaseUrl ?: "https://api.bitwarden.com") }
    var identityUrl by remember { mutableStateOf(currentCredentials?.identityUrl ?: "https://identity.bitwarden.com") }
    var clientId by remember { mutableStateOf(currentCredentials?.clientId ?: "") }
    var clientSecret by remember { mutableStateOf(currentCredentials?.clientSecret ?: "") }
    var folderName by remember { mutableStateOf(currentCredentials?.folderName ?: "AnchorVault") }
    var useSelfHosted by remember { mutableStateOf(currentCredentials?.apiBaseUrl != "https://api.bitwarden.com") }
    var email by remember { mutableStateOf(currentCredentials?.email ?: "") }
    var masterPassword by remember { mutableStateOf(currentCredentials?.masterPassword ?: "") }
    var enableEncryption by remember { mutableStateOf(currentCredentials?.masterPassword != null) }

    var isValidating by remember { mutableStateOf(false) }
    var validationResult by remember { mutableStateOf<String?>(null) }
    var validationSuccess by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text(
                            text = "\u2190",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Bitwarden Sync Section ---
            Text(
                text = "Bitwarden Sync",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Configure your Bitwarden API credentials to enable cross-device sync. "
                    + "Find your Client ID and Secret in Bitwarden > Settings > Security > API Key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Self-hosted toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Use self-hosted server", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = useSelfHosted,
                    onCheckedChange = {
                        useSelfHosted = it
                        if (!it) {
                            apiBaseUrl = "https://api.bitwarden.com"
                            identityUrl = "https://identity.bitwarden.com"
                        }
                    }
                )
            }

            if (useSelfHosted) {
                OutlinedTextField(
                    value = apiBaseUrl,
                    onValueChange = { apiBaseUrl = it },
                    label = { Text("API Base URL") },
                    placeholder = { Text("https://your-bitwarden.com/api") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = identityUrl,
                    onValueChange = { identityUrl = it },
                    label = { Text("Identity URL") },
                    placeholder = { Text("https://your-bitwarden.com/identity") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = clientSecret,
                onValueChange = { clientSecret = it },
                label = { Text("Client Secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Vault Folder Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))

            // --- Vault Encryption Section ---
            Text(
                text = "Vault Encryption (Optional)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Without your master password, synced data is stored unencrypted on the server "
                    + "and will not be visible in the Bitwarden/Vaultwarden web UI. "
                    + "Provide your master password to enable end-to-end encryption — "
                    + "your bookmarks will then appear in the web vault like any other item.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Enable vault encryption", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = enableEncryption,
                    onCheckedChange = { enableEncryption = it }
                )
            }

            if (enableEncryption) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Bitwarden Account Email") },
                    placeholder = { Text("you@example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = masterPassword,
                    onValueChange = { masterPassword = it },
                    label = { Text("Master Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Your master password is stored encrypted on this device and is never sent to any server. "
                        + "It is only used locally to derive encryption keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = {
                    val credentials = BitwardenCredentials(
                        apiBaseUrl = apiBaseUrl.trim(),
                        identityUrl = identityUrl.trim(),
                        clientId = clientId.trim(),
                        clientSecret = clientSecret.trim(),
                        folderName = folderName.trim().ifBlank { "AnchorVault" },
                        masterPassword = if (enableEncryption) masterPassword else null,
                        email = if (enableEncryption) email.trim().lowercase() else null
                    )
                    isValidating = true
                    validationResult = null
                    validationSuccess = false
                    scope.launch {
                        val error = syncService.validateCredentials(credentials)
                        isValidating = false
                        if (error == null) {
                            validationSuccess = true
                            validationResult = "Connection successful — folder '${credentials.folderName}' is ready."
                            onSaveCredentials(credentials)
                        } else {
                            validationSuccess = false
                            validationResult = error
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = clientId.isNotBlank() && clientSecret.isNotBlank() && !isValidating
                    && (!enableEncryption || (email.isNotBlank() && masterPassword.isNotBlank()))
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Text("  Validating...", style = MaterialTheme.typography.labelLarge)
                } else {
                    Text("Save Bitwarden Credentials")
                }
            }

            // Validation result message
            if (validationResult != null) {
                Text(
                    text = validationResult!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (validationSuccess) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()

            // --- Features Section ---
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Features",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Auto-tag bookmarks", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = autoTagEnabled,
                    onCheckedChange = onAutoTagEnabledChanged
                )
            }
            Text(
                text = "When enabled, an \"Auto-tag\" button appears on the bookmark editor. "
                    + "It fetches the page content and suggests relevant tags automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()

            // --- About Section ---
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "AnchorVault — secure bookmark storage with Bitwarden sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Data is stored locally in an encrypted database (SQLCipher on Android) and "
                    + "optionally synced to your Bitwarden vault as Secure Notes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Important: The database encryption key is tied to this device's hardware keystore "
                    + "and cannot be exported or backed up. If you uninstall the app or factory-reset your "
                    + "device, local bookmarks will be lost unless synced to Bitwarden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

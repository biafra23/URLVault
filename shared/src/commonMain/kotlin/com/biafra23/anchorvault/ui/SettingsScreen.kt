package com.biafra23.anchorvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.biafra23.anchorvault.sync.BitwardenCredentials

/**
 * Settings screen for configuring Bitwarden sync credentials and app preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentCredentials: BitwardenCredentials? = null,
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

            Button(
                onClick = {
                    if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
                        onSaveCredentials(
                            BitwardenCredentials(
                                apiBaseUrl = apiBaseUrl.trim(),
                                identityUrl = identityUrl.trim(),
                                clientId = clientId.trim(),
                                clientSecret = clientSecret.trim(),
                                folderName = folderName.trim().ifBlank { "AnchorVault" }
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = clientId.isNotBlank() && clientSecret.isNotBlank()
            ) {
                Text("Save Bitwarden Credentials")
            }

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
                text = "Data is stored locally in an encrypted Room database (SQLCipher) and "
                    + "optionally synced to your Bitwarden vault as Secure Notes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

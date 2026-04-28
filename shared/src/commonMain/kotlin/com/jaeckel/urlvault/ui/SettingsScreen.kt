package com.jaeckel.urlvault.ui

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.jaeckel.urlvault.ai.AiProviderIds
import com.jaeckel.urlvault.ai.ModelCatalogEntry
import com.jaeckel.urlvault.ai.ModelDownloadState
import com.jaeckel.urlvault.ai.ModelRuntime
import com.jaeckel.urlvault.sync.BitwardenCredentials
import com.jaeckel.urlvault.sync.BitwardenSyncService
import com.jaeckel.urlvault.sync.SettingsFieldHistory
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
    aiCoreAvailable: Boolean = false,
    aiCoreEnabled: Boolean = false,
    aiCoreStatusText: String? = null,
    onAiCoreEnabledChanged: (Boolean) -> Unit = {},
    onToggleAiCoreActive: (Boolean) -> Unit = {},
    localModelCatalog: List<ModelCatalogEntry> = emptyList(),
    localModelStates: Map<String, ModelDownloadState> = emptyMap(),
    activeModelIds: Set<String> = emptySet(),
    onDownloadModel: (ModelCatalogEntry) -> Unit = {},
    onCancelModelDownload: (ModelCatalogEntry) -> Unit = {},
    onDeleteModel: (ModelCatalogEntry) -> Unit = {},
    onToggleModelActive: (ModelCatalogEntry, Boolean) -> Unit = { _, _ -> },
    onAddCustomModel: (hfRepo: String, hfFile: String, displayName: String) -> Unit = { _, _, _ -> },
    onOpenComparison: () -> Unit = {},
    onSaveCredentials: (BitwardenCredentials) -> Unit,
    onNavigateBack: () -> Unit,
    fieldHistory: SettingsFieldHistory = SettingsFieldHistory(),
    modifier: Modifier = Modifier
) {
    // Strip the /api suffix so the user sees their base server URL, not the derived API URL
    var serverUrl by remember {
        mutableStateOf(
            currentCredentials?.apiBaseUrl?.removeSuffix("/api")
                ?.takeIf { it != "https://api.bitwarden.com" }
                ?: ""
        )
    }
    var folderName by remember { mutableStateOf(currentCredentials?.folderName ?: "URLVault") }
    var useSelfHosted by remember {
        mutableStateOf(
            currentCredentials != null && currentCredentials.apiBaseUrl != "https://api.bitwarden.com"
        )
    }
    var email by remember { mutableStateOf(currentCredentials?.email ?: "") }
    var masterPassword by remember { mutableStateOf(currentCredentials?.masterPassword ?: "") }

    var masterPasswordVisible by remember { mutableStateOf(false) }

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
                text = "Sign in with your Bitwarden email and master password to enable cross-device sync.",
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
                            serverUrl = ""
                        }
                    }
                )
            }

            if (useSelfHosted) {
                AutocompleteTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = "Server URL",
                    placeholder = "https://your-bitwarden.com",
                    suggestions = fieldHistory.serverUrls,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // --- Email + Master Password ---
            AutocompleteTextField(
                value = email,
                onValueChange = { email = it },
                label = "Bitwarden Account Email",
                placeholder = "you@example.com",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                suggestions = fieldHistory.emails,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = masterPassword,
                onValueChange = { masterPassword = it },
                label = { Text("Master Password") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (masterPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { masterPasswordVisible = !masterPasswordVisible }) {
                        Icon(
                            imageVector = if (masterPasswordVisible) rememberVisibilityOffIcon() else rememberVisibilityIcon(),
                            contentDescription = if (masterPasswordVisible) "Hide master password" else "Show master password"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Your master password is stored encrypted on this device. "
                    + "It is used to authenticate and to derive vault encryption keys.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AutocompleteTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = "Vault Folder Name",
                placeholder = "URLVault",
                suggestions = fieldHistory.folderNames,
                modifier = Modifier.fillMaxWidth()
            )

            val rawServerUrl = serverUrl.trim().trimEnd('/')
            val normalizedServerUrl = if (rawServerUrl.isNotBlank() && !rawServerUrl.contains("://")) {
                "https://$rawServerUrl"
            } else {
                rawServerUrl
            }
            val selfHostedUrlValid = !useSelfHosted || (
                normalizedServerUrl.isNotBlank() &&
                    (normalizedServerUrl.startsWith("https://") || normalizedServerUrl.startsWith("http://")) &&
                    normalizedServerUrl.substringAfter("://").isNotBlank() &&
                    !normalizedServerUrl.substringAfter("://").startsWith("/") &&
                    !normalizedServerUrl.contains(' ')
                )
            val serverUrlError = if (useSelfHosted && !selfHostedUrlValid) {
                "Enter a valid self-hosted server URL."
            } else {
                null
            }
            val formValid = email.isNotBlank() && masterPassword.isNotBlank() && selfHostedUrlValid

            if (serverUrlError != null) {
                Text(
                    text = serverUrlError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    val effectiveApiBaseUrl = if (useSelfHosted) {
                        "$normalizedServerUrl/api"
                    } else {
                        "https://api.bitwarden.com"
                    }
                    val effectiveIdentityUrl = if (useSelfHosted) {
                        "$normalizedServerUrl/identity"
                    } else {
                        "https://identity.bitwarden.com"
                    }
                    val credentials = BitwardenCredentials(
                        apiBaseUrl = effectiveApiBaseUrl,
                        identityUrl = effectiveIdentityUrl,
                        folderName = folderName.trim().ifBlank { "URLVault" },
                        masterPassword = masterPassword,
                        email = email.trim().lowercase()
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
                enabled = formValid && !isValidating
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

            // --- AI Tagging Section ---
            // One master toggle gates AI generation. When on, the user picks
            // exactly one provider — AICore (when available) is shown as the
            // top option, followed by the downloadable local models. AICore
            // and Llama models share the same activeIds set; the toggle behaves
            // like a radio button across the whole list.
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "AI Tagging",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Enable AI tagging", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = aiCoreEnabled,
                    onCheckedChange = onAiCoreEnabledChanged
                )
            }
            Text(
                text = "Generate titles, descriptions, and tags for new bookmarks " +
                    "using an on-device model. No data leaves your device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (aiCoreEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Choose model",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Selecting a model deselects the others. If none is selected, " +
                        "the first available model is used automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Group providers by runtime so each backend (AICore /
                // llama.cpp / Leap / LiteRT-LM) reads as its own subsection.
                // Order matches the user-facing preference order: native first,
                // not-yet-wired runtimes last.
                val groupOrder = listOf(
                    ModelRuntime.ML_KIT,
                    ModelRuntime.LLAMA_CPP,
                    ModelRuntime.LEAP,
                    ModelRuntime.MEDIAPIPE,
                )
                val catalogByRuntime = localModelCatalog.groupBy { it.runtime }
                groupOrder.forEach { runtime ->
                    val entries = catalogByRuntime[runtime].orEmpty()
                    val isAiCoreGroup = runtime == ModelRuntime.ML_KIT && aiCoreAvailable
                    if (entries.isEmpty() && !isAiCoreGroup) return@forEach

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = runtimeSectionLabel(runtime),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (isAiCoreGroup) {
                        AiCoreProviderRow(
                            statusText = aiCoreStatusText,
                            isActive = AiProviderIds.AICORE in activeModelIds,
                            onToggleActive = onToggleAiCoreActive,
                        )
                    }
                    entries.forEach { entry ->
                        val state = localModelStates[entry.id] ?: ModelDownloadState.Idle
                        ModelCatalogRow(
                            entry = entry,
                            state = state,
                            isActive = entry.id in activeModelIds,
                            onDownload = { onDownloadModel(entry) },
                            onCancel = { onCancelModelDownload(entry) },
                            onDelete = { onDeleteModel(entry) },
                            onToggleActive = { onToggleModelActive(entry, it) },
                        )
                    }
                }

                CustomModelEntryRow(onAdd = onAddCustomModel)

                Button(
                    onClick = onOpenComparison,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Run model comparison")
                }
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
                text = "URLVault — secure bookmark storage with Bitwarden sync.",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutocompleteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = suggestions.filter {
        it.contains(value, ignoreCase = true) && it != value
    }

    ExposedDropdownMenuBox(
        expanded = expanded && filtered.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable)
        )
        if (filtered.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                filtered.forEach { suggestion ->
                    DropdownMenuItem(
                        text = { Text(suggestion) },
                        onClick = {
                            onValueChange(suggestion)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}

/**
 * Provider row for the AICore (Gemini Nano) entry. Shares the visual shape of
 * `ModelCatalogRow` so AICore reads as one option among many, but omits the
 * download/delete actions because the model is OS-managed.
 */
@Composable
private fun AiCoreProviderRow(
    statusText: String?,
    isActive: Boolean,
    onToggleActive: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Google Gemini Nano (AICore)",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "on-device • bundled with Android (no download)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = statusText ?: "Status unknown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Active", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.size(4.dp))
                Switch(
                    checked = isActive,
                    onCheckedChange = onToggleActive,
                )
            }
        }
    }
}

@Composable
private fun ModelCatalogRow(
    entry: ModelCatalogEntry,
    state: ModelDownloadState,
    isActive: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${formatBytes(entry.approxBytes)} • ${entry.license}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.notes.isNotBlank()) {
                    Text(
                        text = entry.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        // Status + action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = describeState(state),
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    is ModelDownloadState.Failed -> MaterialTheme.colorScheme.error
                    is ModelDownloadState.Ready -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isInProgress = state is ModelDownloadState.Downloading ||
                    state is ModelDownloadState.Queued ||
                    state is ModelDownloadState.Verifying
                when {
                    state is ModelDownloadState.Ready -> {
                        Text("Active", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.size(4.dp))
                        Switch(
                            checked = isActive,
                            onCheckedChange = onToggleActive,
                        )
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = onDelete) { Text("Delete") }
                    }
                    isInProgress -> {
                        Button(onClick = onCancel) { Text("Cancel") }
                    }
                    else -> {
                        Button(
                            onClick = onDownload,
                            enabled = entry.runtime != ModelRuntime.MEDIAPIPE,
                        ) { Text("Download") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomModelEntryRow(
    onAdd: (hfRepo: String, hfFile: String, displayName: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var hfRepo by remember { mutableStateOf("") }
    var hfFile by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Add custom GGUF from Hugging Face", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = expanded, onCheckedChange = { expanded = it })
        }
        if (expanded) {
            OutlinedTextField(
                value = hfRepo,
                onValueChange = { hfRepo = it },
                label = { Text("HF repo (e.g. LiquidAI/LFM2-1.2B-GGUF)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hfFile,
                onValueChange = { hfFile = it },
                label = { Text("File name (e.g. LFM2-1.2B-Q4_K_M.gguf)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    onAdd(hfRepo.trim(), hfFile.trim(), displayName.trim().ifBlank { "$hfRepo/$hfFile" })
                    hfRepo = ""; hfFile = ""; displayName = ""
                    expanded = false
                },
                enabled = hfRepo.isNotBlank() && hfFile.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add to catalog") }
        }
    }
}

private fun runtimeSectionLabel(runtime: ModelRuntime): String = when (runtime) {
    ModelRuntime.ML_KIT -> "AICore"
    ModelRuntime.LLAMA_CPP -> "llama.cpp"
    ModelRuntime.LEAP -> "Leap"
    ModelRuntime.MEDIAPIPE -> "LiteRT-LM"
}

private fun describeState(state: ModelDownloadState): String = when (state) {
    ModelDownloadState.Idle -> "Not downloaded"
    ModelDownloadState.Queued -> "Queued..."
    is ModelDownloadState.Downloading -> {
        val pct = (state.progress * 100).toInt().coerceIn(0, 100)
        "Downloading $pct% (${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)})"
    }
    ModelDownloadState.Verifying -> "Verifying..."
    is ModelDownloadState.Ready -> "Ready (${formatBytes(state.sizeBytes)})"
    is ModelDownloadState.Failed -> "Failed: ${state.reason}"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.size - 1) {
        value /= 1024.0; unit++
    }
    val rounded = ((value * 10).toLong()) / 10.0
    return "$rounded ${units[unit]}"
}

@Composable
private fun rememberVisibilityIcon(): ImageVector = remember {
    ImageVector.Builder(
        name = "Visibility",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 4.5f)
            curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
            curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
            curveTo(17f, 19.5f, 21.27f, 16.39f, 23f, 12f)
            curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
            close()
            moveTo(12f, 17f)
            curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
            curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 14.76f, 14.76f, 17f, 12f, 17f)
            close()
            moveTo(12f, 9f)
            curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
            close()
        }
    }.build()
}

@Composable
private fun rememberVisibilityOffIcon(): ImageVector = remember {
    ImageVector.Builder(
        name = "VisibilityOff",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 12.65f, 16.87f, 13.26f, 16.64f, 13.83f)
            lineTo(19.56f, 16.75f)
            curveTo(21.07f, 15.49f, 22.26f, 13.86f, 23f, 12f)
            curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
            curveTo(10.6f, 4.5f, 9.26f, 4.75f, 8f, 5.2f)
            lineTo(10.17f, 7.36f)
            curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f)
            close()
            moveTo(2f, 4.27f)
            lineTo(4.28f, 6.55f)
            curveTo(2.59f, 7.88f, 1.23f, 9.63f, 0.41f, 11.63f)
            lineTo(1f, 12f)
            curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
            curveTo(13.55f, 19.5f, 15.03f, 19.2f, 16.38f, 18.66f)
            lineTo(19.73f, 22f)
            lineTo(21f, 20.73f)
            lineTo(3.27f, 3f)
            lineTo(2f, 4.27f)
            close()
            moveTo(7.53f, 9.8f)
            lineTo(9.08f, 11.35f)
            curveTo(9.03f, 11.56f, 9f, 11.78f, 9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(12.22f, 15f, 12.44f, 14.97f, 12.65f, 14.92f)
            lineTo(14.2f, 16.47f)
            curveTo(13.53f, 16.8f, 12.79f, 17f, 12f, 17f)
            curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
            curveTo(7f, 11.21f, 7.2f, 10.47f, 7.53f, 9.8f)
            close()
            moveTo(11.84f, 9.02f)
            lineTo(14.99f, 12.17f)
            lineTo(15.01f, 12.01f)
            curveTo(15.01f, 10.35f, 13.67f, 9.01f, 12.01f, 9.01f)
            lineTo(11.84f, 9.02f)
            close()
        }
    }.build()
}

package com.biafra23.anchorvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.biafra23.anchorvault.model.Bookmark
import com.biafra23.anchorvault.viewmodel.AIGenerationState
import com.biafra23.anchorvault.viewmodel.AutoTagState

/**
 * Screen for adding or editing a bookmark.
 *
 * @param existingBookmark When non-null, the screen enters "edit mode" and prefills fields.
 * @param onSave           Called with the resulting [Bookmark] when the user confirms.
 * @param onCancel         Called when the user dismisses the screen.
 * @param autoTagEnabled   When true, auto-tagging triggers automatically on URL availability.
 * @param autoTagState     Current state of the auto-tag operation.
 * @param onAutoTag        Called with the URL when auto-tag is triggered.
 * @param onAutoTagConsumed Called after auto-tag results have been applied.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditBookmarkScreen(
    existingBookmark: Bookmark? = null,
    prefilledUrl: String? = null,
    onSave: (Bookmark) -> Unit,
    onCancel: () -> Unit,
    autoTagEnabled: Boolean = false,
    autoTagState: AutoTagState = AutoTagState.Idle,
    onAutoTag: ((String) -> Unit)? = null,
    onAutoTagConsumed: () -> Unit = {},
    aiCoreEnabled: Boolean = false,
    aiTagState: AIGenerationState = AIGenerationState.Idle,
    aiDescriptionState: AIGenerationState = AIGenerationState.Idle,
    onAiGenerateTags: ((String, String, String) -> Unit)? = null,
    onAiGenerateDescription: ((String, String) -> Unit)? = null,
    onAiTagConsumed: () -> Unit = {},
    onAiDescriptionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isEditMode = existingBookmark != null

    var url by remember { mutableStateOf(existingBookmark?.url ?: prefilledUrl ?: "") }
    var title by remember { mutableStateOf(existingBookmark?.title ?: "") }
    var description by remember { mutableStateOf(existingBookmark?.description ?: "") }
    var isFavorite by remember { mutableStateOf(existingBookmark?.isFavorite ?: false) }
    val selectedTags = remember { mutableStateListOf<String>().also { list ->
        existingBookmark?.tags?.forEach(list::add)
    }}
    var newTagInput by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var autoTagError by remember { mutableStateOf<String?>(null) }
    var aiTagError by remember { mutableStateOf<String?>(null) }
    var aiDescriptionError by remember { mutableStateOf<String?>(null) }

    // Track which URL we've already triggered AI for, to prevent re-triggering
    var aiTriggeredForUrl by remember { mutableStateOf<String?>(null) }

    // Helper to normalize and validate URL for AI triggering
    fun normalizeUrlForAi(rawUrl: String): String? {
        if (rawUrl.isBlank()) return null
        val normalized = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            "https://$rawUrl"
        } else rawUrl
        // Must contain a dot to look like a real URL
        if (!normalized.contains(".")) return null
        return normalized
    }

    // Helper to trigger AI/autotag for a given URL
    fun triggerAiForUrl(targetUrl: String) {
        if (aiTriggeredForUrl == targetUrl) return
        aiTriggeredForUrl = targetUrl

        if (aiCoreEnabled && onAiGenerateDescription != null) {
            aiDescriptionError = null
            onAiGenerateDescription(targetUrl, title)
        } else if (autoTagEnabled && onAutoTag != null) {
            autoTagError = null
            onAutoTag(targetUrl)
        }
    }

    // Apply auto-tag results when they arrive
    LaunchedEffect(autoTagState) {
        when (autoTagState) {
            is AutoTagState.Success -> {
                autoTagError = null
                autoTagState.tags.forEach { tag ->
                    if (!selectedTags.contains(tag)) selectedTags.add(tag)
                }
                onAutoTagConsumed()
            }
            is AutoTagState.Error -> {
                autoTagError = autoTagState.message
                onAutoTagConsumed()
            }
            else -> {}
        }
    }

    // Apply AI tag results when they arrive
    LaunchedEffect(aiTagState) {
        when (aiTagState) {
            is AIGenerationState.TagsSuccess -> {
                aiTagError = null
                aiTagState.tags.forEach { tag ->
                    if (!selectedTags.contains(tag)) selectedTags.add(tag)
                }
                onAiTagConsumed()
            }
            is AIGenerationState.Error -> {
                aiTagError = aiTagState.message
                onAiTagConsumed()
            }
            else -> {}
        }
    }

    // Apply AI description results when they arrive, then chain-trigger AI tags
    LaunchedEffect(aiDescriptionState) {
        when (aiDescriptionState) {
            is AIGenerationState.DescriptionSuccess -> {
                aiDescriptionError = null
                description = aiDescriptionState.description
                onAiDescriptionConsumed()
                // Chain-trigger AI tags now that we have a description
                if (aiCoreEnabled && onAiGenerateTags != null) {
                    val targetUrl = normalizeUrlForAi(url)
                    if (targetUrl != null) {
                        aiTagError = null
                        onAiGenerateTags(targetUrl, title, aiDescriptionState.description)
                    }
                }
            }
            is AIGenerationState.Error -> {
                aiDescriptionError = aiDescriptionState.message
                onAiDescriptionConsumed()
            }
            else -> {}
        }
    }

    // Auto-trigger for prefilled URLs (share intent) — trigger immediately
    LaunchedEffect(Unit) {
        if (!isEditMode && prefilledUrl != null) {
            val targetUrl = normalizeUrlForAi(prefilledUrl)
            if (targetUrl != null) {
                triggerAiForUrl(targetUrl)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Edit Bookmark" else "Add Bookmark") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Text(
                            text = "\u2190",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (url.isBlank()) {
                                urlError = "URL is required"
                                return@Button
                            }
                            val normalizedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                "https://$url"
                            } else url

                            val urlPattern = Regex("^https?://[^\\s/\$.?#].[^\\s]*$")
                            if (!urlPattern.matches(normalizedUrl)) {
                                urlError = "Please enter a valid URL"
                                return@Button
                            }

                            val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                            val bookmark = Bookmark(
                                id = existingBookmark?.id ?: generateId(),
                                url = normalizedUrl,
                                title = title.ifBlank { normalizedUrl },
                                description = description,
                                tags = selectedTags.toList(),
                                createdAt = existingBookmark?.createdAt ?: now,
                                updatedAt = now,
                                isFavorite = isFavorite
                            )
                            onSave(bookmark)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isEditMode) "Save Changes" else "Save Bookmark")
                    }
                    OutlinedButton(
                        onClick = {
                            // Revert all fields to initial values
                            url = existingBookmark?.url ?: prefilledUrl ?: ""
                            title = existingBookmark?.title ?: ""
                            description = existingBookmark?.description ?: ""
                            isFavorite = existingBookmark?.isFavorite ?: false
                            selectedTags.clear()
                            existingBookmark?.tags?.forEach(selectedTags::add)
                            newTagInput = ""
                            urlError = null
                            autoTagError = null
                            aiTagError = null
                            aiDescriptionError = null
                            aiTriggeredForUrl = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Revert")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // URL field with focus-loss auto-trigger
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; urlError = null },
                label = { Text("URL *") },
                placeholder = { Text("https://example.com") },
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && !isEditMode) {
                            val targetUrl = normalizeUrlForAi(url)
                            if (targetUrl != null) {
                                triggerAiForUrl(targetUrl)
                            }
                        }
                    }
            )

            // Title field
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Description field
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            // Inline loading/error for AI description
            if (aiDescriptionState is AIGenerationState.Loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Generating description...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            aiDescriptionError?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Favorite toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Favorite",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = isFavorite,
                    onCheckedChange = { isFavorite = it }
                )
            }

            // Tags section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Tags",
                    style = MaterialTheme.typography.titleSmall
                )

                if (autoTagEnabled && !isEditMode && url.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            val targetUrl = normalizeUrlForAi(url)
                            if (targetUrl != null) {
                                triggerAiForUrl(targetUrl)
                            }
                        },
                        enabled = autoTagState !is AutoTagState.Loading && aiTagState !is AIGenerationState.Loading,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = if (aiCoreEnabled) "AI Auto-tag" else "Auto-tag",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // Inline loading/error for tags generation
            if (aiTagState is AIGenerationState.Loading || autoTagState is AutoTagState.Loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Generating tags...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            autoTagError?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            aiTagError?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // New tag input
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = newTagInput,
                    onValueChange = { newTagInput = it },
                    label = { Text("Add tag") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val tag = newTagInput.trim()
                        if (tag.isNotBlank() && !selectedTags.contains(tag)) {
                            selectedTags.add(tag)
                        }
                        newTagInput = ""
                    }
                ) {
                    Text("Add")
                }
            }

            // Selected tags chips
            if (selectedTags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedTags.forEach { tag ->
                        InputChip(
                            selected = true,
                            onClick = { selectedTags.remove(tag) },
                            label = { Text(tag) },
                            trailingIcon = {
                                Text(
                                    text = "\u00D7",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Generates a unique bookmark ID using a random UUID. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun generateId(): String = kotlin.uuid.Uuid.random().toString()

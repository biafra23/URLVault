package com.jaeckel.urlvault.ui

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
import com.jaeckel.urlvault.BuildConfig
import com.jaeckel.urlvault.Logger
import com.jaeckel.urlvault.model.Bookmark
import com.jaeckel.urlvault.viewmodel.AIGenerationState
import com.jaeckel.urlvault.viewmodel.AutoTagState

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
    aiTitleState: AIGenerationState = AIGenerationState.Idle,
    onAiGenerateTags: ((String, String, String) -> Unit)? = null,
    onAiGenerateDescription: ((String, String) -> Unit)? = null,
    onAiGenerateTitle: ((String) -> Unit)? = null,
    onAiTagConsumed: () -> Unit = {},
    onAiDescriptionConsumed: () -> Unit = {},
    onAiTitleConsumed: () -> Unit = {},
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
    var aiTitleError by remember { mutableStateOf<String?>(null) }

    // DEBUG: track legacy tags for comparison
    val legacyTags = remember { mutableStateListOf<String>() }

    val TAG = "AddEditBookmarkScreen"

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
    fun triggerAiForUrl(targetUrl: String, force: Boolean = false) {
        Logger.d(TAG, "triggerAiForUrl($targetUrl, force=$force)")
        if (!force && aiTriggeredForUrl == targetUrl) {
            Logger.d(TAG, "Already triggered for $targetUrl")
            return
        }
        aiTriggeredForUrl = targetUrl

        // If AI is available and enabled, use it for title/desc/tags
        if (aiCoreEnabled) {
            // Trigger AI title generation if title is empty
            if (onAiGenerateTitle != null && title.isBlank()) {
                Logger.d(TAG, "Triggering AI title generation")
                aiTitleError = null
                onAiGenerateTitle(targetUrl)
            }

            if (onAiGenerateDescription != null) {
                Logger.d(TAG, "Triggering AI description generation")
                aiDescriptionError = null
                onAiGenerateDescription(targetUrl, title)
            }

            // In DEBUG mode, also trigger legacy extraction for comparison
            if (BuildConfig.DEBUG && onAutoTag != null) {
                Logger.d(TAG, "Triggering legacy metadata extraction (DEBUG comparison)")
                autoTagError = null
                onAutoTag(targetUrl)
            }
        } else if (onAutoTag != null) {
            // Fallback to legacy extraction (even if autoTagEnabled is false,
            // we'll use it for title/description if they are blank).
            Logger.d(TAG, "Triggering legacy metadata extraction")
            autoTagError = null
            onAutoTag(targetUrl)
        } else {
            Logger.d(TAG, "No tagging mechanism available")
        }
    }

    // Apply auto-tag results when they arrive
    LaunchedEffect(autoTagState) {
        when (autoTagState) {
            is AutoTagState.Success -> {
                // Ensure results match current URL to avoid race conditions
                val currentTarget = normalizeUrlForAi(url)
                if (currentTarget != autoTagState.sourceUrl) {
                    Logger.v(TAG, "Ignoring stale legacy results (${autoTagState.sourceUrl} != $currentTarget)")
                    return@LaunchedEffect
                }

                autoTagError = null

                // Apply tags if we found any (respect toggle or manual trigger)
                if (autoTagState.tags.isNotEmpty()) {
                    Logger.d(TAG, "Applying legacy tags: ${autoTagState.tags}")
                    
                    if (BuildConfig.DEBUG) {
                        legacyTags.clear()
                        legacyTags.addAll(autoTagState.tags)
                    }

                    // Only automatically add legacy tags to the main list if AI is NOT handling it
                    if (!aiCoreEnabled) {
                        autoTagState.tags.forEach { tag ->
                            if (!selectedTags.contains(tag)) selectedTags.add(tag)
                        }
                    }
                } else {
                    Logger.d(TAG, "No legacy tags found to apply")
                }

                // If AI isn't handling title/description, use legacy extraction results
                if (!aiCoreEnabled) {
                    if (title.isBlank() && !autoTagState.title.isNullOrBlank()) {
                        Logger.d(TAG, "Applying legacy title: ${autoTagState.title}")
                        title = autoTagState.title
                    }
                    if (description.isBlank() && !autoTagState.description.isNullOrBlank()) {
                        Logger.d(TAG, "Applying legacy description: ${autoTagState.description}")
                        description = autoTagState.description
                    }
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
                // Ensure results match current URL to avoid race conditions
                val currentTarget = normalizeUrlForAi(url)
                if (currentTarget != aiTagState.sourceUrl) return@LaunchedEffect

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
                // Ensure results match current URL to avoid race conditions
                val currentTarget = normalizeUrlForAi(url)
                if (currentTarget != aiDescriptionState.sourceUrl) return@LaunchedEffect

                aiDescriptionError = null
                description = aiDescriptionState.description
                onAiDescriptionConsumed()
                // Chain-trigger AI tags now that we have a description
                if (aiCoreEnabled && onAiGenerateTags != null) {
                    aiTagError = null
                    onAiGenerateTags(currentTarget!!, title, description)
                }
            }
            is AIGenerationState.Error -> {
                aiDescriptionError = aiDescriptionState.message
                onAiDescriptionConsumed()
            }
            else -> {}
        }
    }

    // Apply AI title results when they arrive
    LaunchedEffect(aiTitleState) {
        when (aiTitleState) {
            is AIGenerationState.TitleSuccess -> {
                // Ensure results match current URL to avoid race conditions
                val currentTarget = normalizeUrlForAi(url)
                if (currentTarget != aiTitleState.sourceUrl) return@LaunchedEffect

                aiTitleError = null
                // Only overwrite if current title is still empty
                if (title.isBlank()) {
                    title = aiTitleState.title
                }
                onAiTitleConsumed()
            }
            is AIGenerationState.Error -> {
                aiTitleError = aiTitleState.message
                onAiTitleConsumed()
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
                            legacyTags.clear()
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
                            Logger.d(TAG, "URL field lost focus. URL=$url")
                            val targetUrl = normalizeUrlForAi(url)
                            if (targetUrl != null) {
                                triggerAiForUrl(targetUrl)
                            } else {
                                Logger.d(TAG, "Invalid URL on focus loss: $url")
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

            // Inline loading/error for AI title
            if (aiTitleState is AIGenerationState.Loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Extracting title...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            aiTitleError?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

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
                                // Reset guard if user manually clicks
                                if (aiTriggeredForUrl == targetUrl) {
                                    aiTriggeredForUrl = null
                                }
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

            // DEBUG: Show legacy tags for comparison
            if (BuildConfig.DEBUG && aiTriggeredForUrl != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Legacy Tags (Debug Comparison)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (legacyTags.isEmpty()) {
                    Text(
                        text = if (autoTagState is AutoTagState.Loading) "Extracting..." else "(no legacy tags found)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        legacyTags.forEach { tag ->
                            InputChip(
                                selected = false,
                                onClick = { 
                                    if (!selectedTags.contains(tag)) selectedTags.add(tag)
                                },
                                label = { Text(tag) }
                            )
                        }
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

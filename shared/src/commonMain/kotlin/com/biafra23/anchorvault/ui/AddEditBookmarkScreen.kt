package com.biafra23.anchorvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.biafra23.anchorvault.model.Bookmark

/**
 * Screen for adding or editing a bookmark.
 *
 * @param existingBookmark When non-null, the screen enters "edit mode" and prefills fields.
 * @param existingTags     All available tags for suggestion chips.
 * @param onSave           Called with the resulting [Bookmark] when the user confirms.
 * @param onCancel         Called when the user dismisses the screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditBookmarkScreen(
    existingBookmark: Bookmark? = null,
    prefilledUrl: String? = null,
    existingTags: List<String> = emptyList(),
    onSave: (Bookmark) -> Unit,
    onCancel: () -> Unit,
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // URL field
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; urlError = null },
                label = { Text("URL *") },
                placeholder = { Text("https://example.com") },
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
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
            Text(
                text = "Tags",
                style = MaterialTheme.typography.titleSmall
            )

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

            // Suggested tags (existing tags not yet selected)
            val suggestedTags = existingTags.filter { !selectedTags.contains(it) }
            if (suggestedTags.isNotEmpty()) {
                Text(
                    text = "Suggestions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suggestedTags.forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = { selectedTags.add(tag) },
                            label = { Text(tag) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Save button
            Button(
                onClick = {
                    if (url.isBlank()) {
                        urlError = "URL is required"
                        return@Button
                    }
                    val normalizedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        "https://$url"
                    } else url

                    // Basic URL format validation
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isEditMode) "Save Changes" else "Save Bookmark")
            }
        }
    }
}

/** Generates a unique bookmark ID using a random UUID. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun generateId(): String = kotlin.uuid.Uuid.random().toString()

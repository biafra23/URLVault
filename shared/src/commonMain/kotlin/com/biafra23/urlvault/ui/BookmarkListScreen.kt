package com.biafra23.urlvault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biafra23.urlvault.model.Bookmark
import com.biafra23.urlvault.viewmodel.BookmarkListUiState
import com.biafra23.urlvault.viewmodel.BookmarkViewModel
import com.biafra23.urlvault.viewmodel.SyncStatus

/**
 * Main screen that shows the list of bookmarks with tag filtering and search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkListScreen(
    viewModel: BookmarkViewModel,
    onAddBookmark: () -> Unit,
    onEditBookmark: (Bookmark) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show errors via Snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    // Show sync success or error
    LaunchedEffect(uiState.syncStatus) {
        when (val status = uiState.syncStatus) {
            is SyncStatus.Success -> {
                snackbarHostState.showSnackbar("Sync completed successfully")
                viewModel.clearError()
            }
            is SyncStatus.Error -> {
                snackbarHostState.showSnackbar("Sync failed: ${status.message}")
                viewModel.clearError()
            }
            else -> {}
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("URLVault") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    // Sync button
                    IconButton(
                        onClick = { viewModel.syncWithBitwarden() },
                        enabled = uiState.syncStatus !is SyncStatus.Syncing
                    ) {
                        if (uiState.syncStatus is SyncStatus.Syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "\uD83D\uDD04",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    // Settings button
                    IconButton(onClick = onOpenSettings) {
                        Text(
                            text = "\u2699\uFE0F",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddBookmark) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        val uriHandler = LocalUriHandler.current
        BookmarkListContent(
            uiState = uiState,
            onTagSelected = viewModel::selectTag,
            onSearchQueryChanged = viewModel::updateSearchQuery,
            onOpenUrl = { url -> uriHandler.openUri(url) },
            onEditBookmark = onEditBookmark,
            onDeleteBookmark = viewModel::deleteBookmark,
            onToggleFavorite = viewModel::toggleFavorite,
            modifier = Modifier.padding(paddingValues)
        )
    }
}

@Composable
private fun BookmarkListContent(
    uiState: BookmarkListUiState,
    onTagSelected: (String?) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onEditBookmark: (Bookmark) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onToggleFavorite: (Bookmark) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Search bar
        SearchBar(
            query = uiState.searchQuery,
            onQueryChanged = onSearchQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Tag filter chips
        AnimatedVisibility(visible = uiState.allTags.isNotEmpty()) {
            TagFilterRow(
                tags = uiState.allTags,
                selectedTag = uiState.selectedTag,
                onTagSelected = onTagSelected,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Bookmark list
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.bookmarks.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = uiState.bookmarks,
                    key = { it.id }
                ) { bookmark ->
                    BookmarkCard(
                        bookmark = bookmark,
                        onOpen = { onOpenUrl(bookmark.url) },
                        onEdit = { onEditBookmark(bookmark) },
                        onDelete = { onDeleteBookmark(bookmark.id) },
                        onToggleFavorite = { onToggleFavorite(bookmark) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChanged,
        placeholder = { Text("Search bookmarks…") },
        singleLine = true,
        modifier = modifier,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun TagFilterRow(
    tags: List<String>,
    selectedTag: String?,
    onTagSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = selectedTag == null,
                onClick = { onTagSelected(null) },
                label = { Text("All") }
            )
        }
        items(tags) { tag ->
            FilterChip(
                selected = selectedTag == tag,
                onClick = { onTagSelected(tag) },
                label = { Text(tag) }
            )
        }
    }
}

@Composable
private fun BookmarkCard(
    bookmark: Bookmark,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bookmark.title.ifBlank { bookmark.url },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = bookmark.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Favorite star
                Text(
                    text = if (bookmark.isFavorite) "\u2B50" else "\u2606",
                    modifier = Modifier
                        .clickable { onToggleFavorite() }
                        .padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                // Overflow menu
                Box {
                    Text(
                        text = "\u22EE",
                        modifier = Modifier
                            .clickable { showMenu = true }
                            .padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.titleLarge
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = { onEdit(); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { onDelete(); showMenu = false }
                        )
                    }
                }
            }

            if (bookmark.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = bookmark.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (bookmark.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(bookmark.tags) { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = tag,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "\u2693",
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No bookmarks yet",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tap + to add your first URL",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

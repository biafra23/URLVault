package com.biafra23.urlvault.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biafra23.urlvault.autotag.AutoTagService
import com.biafra23.urlvault.model.Bookmark
import com.biafra23.urlvault.repository.BookmarkRepository
import com.biafra23.urlvault.sync.BitwardenCredentials
import com.biafra23.urlvault.sync.BitwardenSyncService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the bookmark list screen.
 */
data class BookmarkListUiState(
    val bookmarks: List<Bookmark> = emptyList(),
    val allTags: List<String> = emptyList(),
    val selectedTag: String? = null,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val errorMessage: String? = null
)

/**
 * Represents the current state of a Bitwarden sync operation.
 */
sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Syncing : SyncStatus()
    data object Success : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

/**
 * Represents the current state of an auto-tag operation.
 */
sealed class AutoTagState {
    data object Idle : AutoTagState()
    data object Loading : AutoTagState()
    data class Success(
        val tags: List<String>,
        val title: String? = null,
        val description: String? = null,
        val sourceUrl: String? = null
    ) : AutoTagState()
    data class Error(val message: String) : AutoTagState()
}

/**
 * Represents the current state of an AI generation operation (tags or description).
 */
sealed class AIGenerationState {
    data object Idle : AIGenerationState()
    data object Loading : AIGenerationState()
    data class TagsSuccess(val tags: List<String>, val sourceUrl: String? = null) : AIGenerationState()
    data class DescriptionSuccess(val description: String, val sourceUrl: String? = null) : AIGenerationState()
    data class TitleSuccess(val title: String, val sourceUrl: String? = null) : AIGenerationState()
    data class Error(val message: String) : AIGenerationState()
}

/** Internal holder for tag/search filter parameters. */
private data class FilterState(val selectedTag: String?, val searchQuery: String)

/**
 * ViewModel for managing bookmark list state and user interactions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkViewModel(
    private val repository: BookmarkRepository,
    private val syncService: BitwardenSyncService,
    private val autoTagService: AutoTagService? = null,
    private val aiTagGenerator: (suspend (String, String, String) -> Result<List<String>>)? = null,
    private val aiDescriptionGenerator: (suspend (String, String) -> Result<String>)? = null,
    private val aiTitleGenerator: (suspend (String) -> Result<String>)? = null
) : ViewModel() {

    private val _selectedTag = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _autoTagState = MutableStateFlow<AutoTagState>(AutoTagState.Idle)
    private val _aiTagState = MutableStateFlow<AIGenerationState>(AIGenerationState.Idle)
    private val _aiDescriptionState = MutableStateFlow<AIGenerationState>(AIGenerationState.Idle)
    private val _aiTitleState = MutableStateFlow<AIGenerationState>(AIGenerationState.Idle)

    val autoTagState: StateFlow<AutoTagState> = _autoTagState.asStateFlow()
    val aiTagState: StateFlow<AIGenerationState> = _aiTagState.asStateFlow()
    val aiDescriptionState: StateFlow<AIGenerationState> = _aiDescriptionState.asStateFlow()
    val aiTitleState: StateFlow<AIGenerationState> = _aiTitleState.asStateFlow()

    /**
     * Combines tag and search query into a single flow so that [flatMapLatest] below
     * correctly reacts to changes in either parameter.
     */
    private val _filterState: StateFlow<FilterState> = combine(
        _selectedTag, _searchQuery
    ) { tag, query -> FilterState(tag, query) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = FilterState(null, "")
        )

    val uiState: StateFlow<BookmarkListUiState> = combine(
        _filterState.flatMapLatest { (tag, query) ->
            if (query.isBlank()) {
                repository.getBookmarks(tag)
            } else {
                // Apply both search query and tag filter simultaneously
                repository.searchBookmarks(query).map { results ->
                    if (tag != null) results.filter { it.tags.contains(tag) } else results
                }
            }
        },
        repository.getAllTags(),
        _filterState,
        _syncStatus,
        _errorMessage
    ) { bookmarks, allTags, filterState, syncStatus, errorMessage ->
        BookmarkListUiState(
            bookmarks = bookmarks,
            allTags = allTags,
            selectedTag = filterState.selectedTag,
            searchQuery = filterState.searchQuery,
            syncStatus = syncStatus,
            errorMessage = errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BookmarkListUiState(isLoading = true)
    )

    fun selectTag(tag: String?) {
        _selectedTag.update { if (it == tag) null else tag }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun addBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.upsertBookmark(bookmark)
        }
    }

    fun updateBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.upsertBookmark(bookmark)
        }
    }

    fun deleteBookmark(bookmarkId: String) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmarkId)
        }
    }

    fun toggleFavorite(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.upsertBookmark(bookmark.copy(isFavorite = !bookmark.isFavorite))
        }
    }

    fun configureBitwarden(credentials: BitwardenCredentials) {
        viewModelScope.launch {
            syncService.configure(credentials)
        }
    }

    fun syncWithBitwarden() {
        viewModelScope.launch {
            if (!syncService.isConfigured()) {
                _errorMessage.value = "Bitwarden is not configured. Please add your API credentials in Settings."
                return@launch
            }
            _syncStatus.value = SyncStatus.Syncing
            // Get the full unfiltered bookmark list from the repository, not the filtered UI state
            val allBookmarks = repository.getBookmarks(null).first()
            val result = syncService.syncAll(allBookmarks)
            result.fold(
                onSuccess = { mergedBookmarks ->
                    mergedBookmarks.forEach { repository.upsertBookmark(it) }
                    _syncStatus.value = SyncStatus.Success
                },
                onFailure = { error ->
                    _syncStatus.value = SyncStatus.Error(error.message ?: "Sync failed")
                }
            )
        }
    }

    fun fetchAutoTags(url: String) {
        val service = autoTagService ?: return
        viewModelScope.launch {
            _autoTagState.value = AutoTagState.Loading
            service.fetchMetadata(url).fold(
                onSuccess = { metadata ->
                    _autoTagState.value = AutoTagState.Success(
                        tags = metadata.tags,
                        title = metadata.title,
                        description = metadata.description,
                        sourceUrl = url
                    )
                },
                onFailure = { e ->
                    _autoTagState.value = AutoTagState.Error(e.message ?: "Failed to fetch tags")
                }
            )
        }
    }

    fun clearAutoTagState() {
        _autoTagState.value = AutoTagState.Idle
    }

    fun generateAiTags(url: String, title: String, description: String) {
        val generator = aiTagGenerator ?: return
        viewModelScope.launch {
            _aiTagState.value = AIGenerationState.Loading
            generator(url, title, description).fold(
                onSuccess = { tags ->
                    _aiTagState.value = if (tags.isEmpty()) {
                        AIGenerationState.Error("AI could not generate tags for this bookmark")
                    } else {
                        AIGenerationState.TagsSuccess(tags, sourceUrl = url)
                    }
                },
                onFailure = { e ->
                    _aiTagState.value = AIGenerationState.Error(e.message ?: "AI tag generation failed")
                }
            )
        }
    }

    fun generateAiDescription(url: String, title: String) {
        val generator = aiDescriptionGenerator ?: return
        viewModelScope.launch {
            _aiDescriptionState.value = AIGenerationState.Loading
            generator(url, title).fold(
                onSuccess = { desc ->
                    _aiDescriptionState.value = AIGenerationState.DescriptionSuccess(desc, sourceUrl = url)
                },
                onFailure = { e ->
                    _aiDescriptionState.value = AIGenerationState.Error(e.message ?: "AI description generation failed")
                }
            )
        }
    }

    fun generateAiTitle(url: String) {
        val generator = aiTitleGenerator ?: return
        viewModelScope.launch {
            _aiTitleState.value = AIGenerationState.Loading
            generator(url).fold(
                onSuccess = { title ->
                    _aiTitleState.value = AIGenerationState.TitleSuccess(title, sourceUrl = url)
                },
                onFailure = { e ->
                    _aiTitleState.value = AIGenerationState.Error(e.message ?: "AI title generation failed")
                }
            )
        }
    }

    fun clearAiTagState() {
        _aiTagState.value = AIGenerationState.Idle
    }

    fun clearAiDescriptionState() {
        _aiDescriptionState.value = AIGenerationState.Idle
    }

    fun clearAiTitleState() {
        _aiTitleState.value = AIGenerationState.Idle
    }

    fun clearError() {
        _errorMessage.value = null
        if (_syncStatus.value is SyncStatus.Error) {
            _syncStatus.value = SyncStatus.Idle
        }
    }
}

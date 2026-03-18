package com.biafra23.anchorvault.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biafra23.anchorvault.model.Bookmark
import com.biafra23.anchorvault.repository.BookmarkRepository
import com.biafra23.anchorvault.sync.BitwardenCredentials
import com.biafra23.anchorvault.sync.BitwardenSyncService
import com.biafra23.anchorvault.sync.SyncResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
 * ViewModel for managing bookmark list state and user interactions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkViewModel(
    private val repository: BookmarkRepository,
    private val syncService: BitwardenSyncService
) : ViewModel() {

    private val _selectedTag = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<BookmarkListUiState> = combine(
        _selectedTag.flatMapLatest { tag ->
            if (_searchQuery.value.isBlank()) {
                repository.getBookmarks(tag)
            } else {
                repository.searchBookmarks(_searchQuery.value)
            }
        },
        repository.getAllTags(),
        _selectedTag,
        _searchQuery,
        _syncStatus,
        _errorMessage
    ) { bookmarks, allTags, selectedTag, searchQuery, syncStatus, errorMessage ->
        BookmarkListUiState(
            bookmarks = bookmarks,
            allTags = allTags,
            selectedTag = selectedTag,
            searchQuery = searchQuery,
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
            val currentBookmarks = uiState.value.bookmarks
            val result = syncService.syncAll(currentBookmarks)
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

    fun clearError() {
        _errorMessage.value = null
        if (_syncStatus.value is SyncStatus.Error) {
            _syncStatus.value = SyncStatus.Idle
        }
    }
}

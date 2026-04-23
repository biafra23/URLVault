package com.biafra23.anchorvault.database

import com.biafra23.anchorvault.model.Bookmark
import com.biafra23.anchorvault.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [BookmarkRepository] implementation for iOS.
 * Persistence is handled by the Kotlin/Native runtime or can be replaced with
 * a CoreData / SQLite implementation in a production iOS app.
 */
class IosBookmarkRepository : BookmarkRepository {

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())

    override fun getBookmarks(tag: String?): Flow<List<Bookmark>> =
        if (tag == null) {
            _bookmarks
        } else {
            _bookmarks.map { list -> list.filter { it.tags.contains(tag) } }
        }

    override fun getAllTags(): Flow<List<String>> =
        _bookmarks.map { bookmarks ->
            bookmarks.flatMap { it.tags }
                .map { tag ->
                    tag.trim()
                        .replace(Regex("[\\\\\\[\\]\\\"']"), "")
                        .trim()
                }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }

    override suspend fun getBookmarkById(id: String): Bookmark? =
        _bookmarks.value.firstOrNull { it.id == id }

    override suspend fun upsertBookmark(bookmark: Bookmark) {
        val sanitizedTags = bookmark.tags.map { tag ->
            tag.trim()
                .replace(Regex("[\\\\\\[\\]\\\"']"), "")
                .trim()
        }.filter { it.isNotBlank() }

        val sanitizedBookmark = bookmark.copy(tags = sanitizedTags)

        _bookmarks.value = _bookmarks.value
            .filterNot { it.id == bookmark.id }
            .plus(sanitizedBookmark)
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun deleteBookmark(id: String) {
        _bookmarks.value = _bookmarks.value.filterNot { it.id == id }
    }

    override fun searchBookmarks(query: String): Flow<List<Bookmark>> =
        _bookmarks.map { bookmarks ->
            val lower = query.lowercase()
            bookmarks.filter {
                it.url.lowercase().contains(lower) ||
                    it.title.lowercase().contains(lower) ||
                    it.description.lowercase().contains(lower)
            }
        }
}

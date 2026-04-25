package com.jaeckel.urlvault.repository

import com.jaeckel.urlvault.model.Bookmark
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for bookmark CRUD operations.
 * Implementations are provided per platform (Room for Android, SQLite for Desktop).
 */
interface BookmarkRepository {
    /**
     * Returns a flow of all bookmarks, optionally filtered by [tag].
     */
    fun getBookmarks(tag: String? = null): Flow<List<Bookmark>>

    /**
     * Returns a flow of all unique tags across all bookmarks.
     */
    fun getAllTags(): Flow<List<String>>

    /**
     * Returns a single bookmark by [id], or null if not found.
     */
    suspend fun getBookmarkById(id: String): Bookmark?

    /**
     * Inserts a new bookmark or replaces an existing one with the same [id].
     */
    suspend fun upsertBookmark(bookmark: Bookmark)

    /**
     * Deletes the bookmark with the given [id].
     */
    suspend fun deleteBookmark(id: String)

    /**
     * Searches bookmarks whose url, title, or description contain [query] (case-insensitive).
     */
    fun searchBookmarks(query: String): Flow<List<Bookmark>>
}

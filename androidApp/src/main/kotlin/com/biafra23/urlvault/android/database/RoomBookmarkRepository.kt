package com.biafra23.urlvault.android.database

import com.biafra23.urlvault.model.Bookmark
import com.biafra23.urlvault.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [BookmarkRepository].
 * All data is persisted in the encrypted SQLCipher database via [BookmarkDao].
 */
class RoomBookmarkRepository(private val dao: BookmarkDao) : BookmarkRepository {

    override fun getBookmarks(tag: String?): Flow<List<Bookmark>> =
        if (tag == null) {
            dao.getAllBookmarks().map { list -> list.map { it.toDomain() } }
        } else {
            dao.getBookmarksByTag(tag).map { list -> list.map { it.toDomain() } }
        }

    override fun getAllTags(): Flow<List<String>> =
        dao.getAllTagStrings().map { tagStrings ->
            tagStrings
                .flatMap { raw ->
                    if (raw.isBlank()) {
                        emptyList()
                    } else if (raw.startsWith("[")) {
                        // JSON array format
                        runCatching { 
                            kotlinx.serialization.json.Json.decodeFromString<List<String>>(raw) 
                        }.getOrElse { emptyList() }
                    } else {
                        // Legacy comma-separated format
                        raw.split(",").map { it.trim() }
                    }
                }
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
        dao.getBookmarkById(id)?.toDomain()

    override suspend fun upsertBookmark(bookmark: Bookmark) =
        dao.upsert(BookmarkEntity.fromDomain(bookmark))

    override suspend fun deleteBookmark(id: String) =
        dao.deleteById(id)

    override fun searchBookmarks(query: String): Flow<List<Bookmark>> =
        dao.searchBookmarks(query).map { list -> list.map { it.toDomain() } }
}

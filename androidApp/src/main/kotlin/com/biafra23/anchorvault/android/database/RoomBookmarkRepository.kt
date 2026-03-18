package com.biafra23.anchorvault.android.database

import com.biafra23.anchorvault.model.Bookmark
import com.biafra23.anchorvault.repository.BookmarkRepository
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
                .flatMap { it.split(",") }
                .map { it.trim() }
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

package com.biafra23.anchorvault.desktop

import com.biafra23.anchorvault.model.Bookmark
import com.biafra23.anchorvault.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite-backed implementation of [BookmarkRepository] for Desktop.
 *
 * Uses the xerial JDBC driver (org.xerial:sqlite-jdbc) for persistence.
 * The database file is stored in the user's home directory under `.anchorvault/`.
 */
class DesktopBookmarkRepository : BookmarkRepository {

    private val dbPath: String = run {
        val home = System.getProperty("user.home")
        val dir = java.io.File("$home/.anchorvault")
        dir.mkdirs()
        "$home/.anchorvault/bookmarks.db"
    }

    private val connection: Connection by lazy {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$dbPath").also { conn ->
            conn.createStatement().execute(
                """
                CREATE TABLE IF NOT EXISTS bookmarks (
                    id TEXT PRIMARY KEY,
                    url TEXT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    tags TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    is_favorite INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    private val mutex = Mutex()
    private val _bookmarksFlow = MutableStateFlow<List<Bookmark>>(emptyList())

    init {
        refreshFlow()
    }

    private fun refreshFlow() {
        _bookmarksFlow.value = queryAll()
    }

    private fun queryAll(): List<Bookmark> {
        val rs = connection.createStatement()
            .executeQuery("SELECT * FROM bookmarks ORDER BY updated_at DESC")
        val results = mutableListOf<Bookmark>()
        while (rs.next()) {
            results.add(rs.toBookmark())
        }
        return results
    }

    override fun getBookmarks(tag: String?): Flow<List<Bookmark>> =
        if (tag == null) {
            _bookmarksFlow
        } else {
            _bookmarksFlow.map { list ->
                list.filter { bookmark -> bookmark.tags.contains(tag) }
            }
        }

    override fun getAllTags(): Flow<List<String>> =
        _bookmarksFlow.map { bookmarks ->
            bookmarks.flatMap { it.tags }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }

    override suspend fun getBookmarkById(id: String): Bookmark? =
        mutex.withLock {
            val ps = connection.prepareStatement("SELECT * FROM bookmarks WHERE id = ? LIMIT 1")
            ps.setString(1, id)
            val rs = ps.executeQuery()
            if (rs.next()) rs.toBookmark() else null
        }

    override suspend fun upsertBookmark(bookmark: Bookmark) {
        mutex.withLock {
            val ps = connection.prepareStatement(
                """
                INSERT INTO bookmarks (id, url, title, description, tags, created_at, updated_at, is_favorite)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    url = excluded.url,
                    title = excluded.title,
                    description = excluded.description,
                    tags = excluded.tags,
                    updated_at = excluded.updated_at,
                    is_favorite = excluded.is_favorite
                """.trimIndent()
            )
            ps.setString(1, bookmark.id)
            ps.setString(2, bookmark.url)
            ps.setString(3, bookmark.title)
            ps.setString(4, bookmark.description)
            ps.setString(5, bookmark.tags.joinToString(","))
            ps.setLong(6, bookmark.createdAt)
            ps.setLong(7, bookmark.updatedAt)
            ps.setInt(8, if (bookmark.isFavorite) 1 else 0)
            ps.executeUpdate()
        }
        refreshFlow()
    }

    override suspend fun deleteBookmark(id: String) {
        mutex.withLock {
            val ps = connection.prepareStatement("DELETE FROM bookmarks WHERE id = ?")
            ps.setString(1, id)
            ps.executeUpdate()
        }
        refreshFlow()
    }

    override fun searchBookmarks(query: String): Flow<List<Bookmark>> =
        _bookmarksFlow.map { bookmarks ->
            val lower = query.lowercase()
            bookmarks.filter {
                it.url.lowercase().contains(lower) ||
                    it.title.lowercase().contains(lower) ||
                    it.description.lowercase().contains(lower)
            }
        }

    private fun java.sql.ResultSet.toBookmark() = Bookmark(
        id = getString("id"),
        url = getString("url"),
        title = getString("title"),
        description = getString("description") ?: "",
        tags = getString("tags")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList(),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        isFavorite = getInt("is_favorite") == 1
    )
}

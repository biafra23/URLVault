package com.jaeckel.urlvault.desktop

import com.jaeckel.urlvault.model.Bookmark
import com.jaeckel.urlvault.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite-backed implementation of [BookmarkRepository] for Desktop.
 *
 * Uses the xerial JDBC driver (org.xerial:sqlite-jdbc) for persistence.
 * The database file is stored in the user's home directory under `.urlvault/`.
 */
class DesktopBookmarkRepository : BookmarkRepository, Closeable {

    private val dbPath: String = run {
        val home = System.getProperty("user.home")
        val dir = java.io.File("$home/.urlvault")
        dir.mkdirs()
        "$home/.urlvault/bookmarks.db"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val connection: Connection by lazy {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$dbPath").also { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
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
    }

    private val mutex = Mutex()
    private val _bookmarksFlow = MutableStateFlow<List<Bookmark>>(emptyList())

    init {
        // Safe to call outside mutex during init — single-threaded at construction time
        _bookmarksFlow.value = queryAll()
    }

    private fun refreshFlow() {
        _bookmarksFlow.value = queryAll()
    }

    private fun queryAll(): List<Bookmark> {
        val results = mutableListOf<Bookmark>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM bookmarks ORDER BY updated_at DESC").use { rs ->
                while (rs.next()) {
                    results.add(rs.toBookmark())
                }
            }
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
        mutex.withLock {
            connection.prepareStatement("SELECT * FROM bookmarks WHERE id = ? LIMIT 1").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.toBookmark() else null
                }
            }
        }

    override suspend fun upsertBookmark(bookmark: Bookmark) {
        mutex.withLock {
            // Ensure tags are clean before saving
            val sanitizedTags = bookmark.tags.map { tag ->
                tag.trim()
                    .replace(Regex("[\\\\\\[\\]\\\"']"), "")
                    .trim()
            }.filter { it.isNotBlank() }

            connection.prepareStatement(
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
            ).use { ps ->
                ps.setString(1, bookmark.id)
                ps.setString(2, bookmark.url)
                ps.setString(3, bookmark.title)
                ps.setString(4, bookmark.description)
                ps.setString(5, json.encodeToString(sanitizedTags))
                ps.setLong(6, bookmark.createdAt)
                ps.setLong(7, bookmark.updatedAt)
                ps.setInt(8, if (bookmark.isFavorite) 1 else 0)
                ps.executeUpdate()
            }
            refreshFlow()
        }
    }

    override suspend fun deleteBookmark(id: String) {
        mutex.withLock {
            connection.prepareStatement("DELETE FROM bookmarks WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            refreshFlow()
        }
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

    override fun close() {
        if (!connection.isClosed) {
            connection.close()
        }
    }

    private fun java.sql.ResultSet.toBookmark(): Bookmark {
        val tagsRaw = getString("tags") ?: ""
        val tagsList = if (tagsRaw.isBlank()) {
            emptyList()
        } else if (tagsRaw.startsWith("[")) {
            // JSON array format (new)
            runCatching { json.decodeFromString<List<String>>(tagsRaw) }.getOrElse { emptyList() }
        } else {
            // Legacy comma-separated format
            tagsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }

        // Sanitize every tag to ensure no brackets or quotes remain from previous bugs
        val sanitizedTags = tagsList.map { tag ->
            tag.trim()
                .replace(Regex("[\\\\\\[\\]\\\"']"), "")
                .trim()
        }.filter { it.isNotBlank() }

        return Bookmark(
            id = getString("id"),
            url = getString("url"),
            title = getString("title"),
            description = getString("description") ?: "",
            tags = sanitizedTags,
            createdAt = getLong("created_at"),
            updatedAt = getLong("updated_at"),
            isFavorite = getInt("is_favorite") == 1
        )
    }
}

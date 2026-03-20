package com.biafra23.anchorvault.android.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.biafra23.anchorvault.model.Bookmark
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room entity that mirrors the [Bookmark] domain model.
 *
 * Tags are stored as a JSON array string, e.g. `["android","kotlin","dev"]`.
 */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val description: String,
    /** JSON array of tags, e.g. `["android","kotlin","dev"]` */
    val tags: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean
) {
    fun toDomain(): Bookmark {
        val tagsList = if (tags.isBlank()) {
            emptyList()
        } else if (tags.startsWith("[")) {
            // JSON array format (new)
            runCatching { json.decodeFromString<List<String>>(tags) }.getOrElse { emptyList() }
        } else {
            // Legacy comma-separated format for backwards compatibility
            tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
        return Bookmark(
            id = id,
            url = url,
            title = title,
            description = description,
            tags = tagsList,
            createdAt = createdAt,
            updatedAt = updatedAt,
            isFavorite = isFavorite
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromDomain(bookmark: Bookmark): BookmarkEntity = BookmarkEntity(
            id = bookmark.id,
            url = bookmark.url,
            title = bookmark.title,
            description = bookmark.description,
            tags = json.encodeToString(bookmark.tags),
            createdAt = bookmark.createdAt,
            updatedAt = bookmark.updatedAt,
            isFavorite = bookmark.isFavorite
        )
    }
}

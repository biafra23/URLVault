package com.biafra23.anchorvault.android.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.biafra23.anchorvault.model.Bookmark

/**
 * Room entity that mirrors the [Bookmark] domain model.
 *
 * Tags are stored as a comma-separated string and converted on read/write.
 */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val description: String,
    /** Comma-separated list of tags, e.g. "android,kotlin,dev" */
    val tags: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean
) {
    fun toDomain(): Bookmark = Bookmark(
        id = id,
        url = url,
        title = title,
        description = description,
        tags = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() },
        createdAt = createdAt,
        updatedAt = updatedAt,
        isFavorite = isFavorite
    )

    companion object {
        fun fromDomain(bookmark: Bookmark): BookmarkEntity = BookmarkEntity(
            id = bookmark.id,
            url = bookmark.url,
            title = bookmark.title,
            description = bookmark.description,
            tags = bookmark.tags.joinToString(","),
            createdAt = bookmark.createdAt,
            updatedAt = bookmark.updatedAt,
            isFavorite = bookmark.isFavorite
        )
    }
}

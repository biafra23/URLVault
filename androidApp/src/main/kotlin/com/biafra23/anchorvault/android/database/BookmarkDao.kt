package com.biafra23.anchorvault.android.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY updatedAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    /**
     * Returns bookmarks that contain [tag] in their comma-separated tags column.
     * The pattern matches whole-word tags to avoid partial matches (e.g. "dev" vs "development").
     */
    @Query(
        """
        SELECT * FROM bookmarks 
        WHERE (',' || tags || ',') LIKE ('%,' || :tag || ',%')
        ORDER BY updatedAt DESC
        """
    )
    fun getBookmarksByTag(tag: String): Flow<List<BookmarkEntity>>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE url LIKE '%' || :query || '%'
           OR title LIKE '%' || :query || '%'
           OR description LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
        """
    )
    fun searchBookmarks(query: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getBookmarkById(id: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Returns all distinct non-empty tag values by splitting the comma-separated column.
     * This uses a CTE approach: Room/SQLite does not natively support array operations,
     * so unique tags are extracted at the application layer in [RoomBookmarkRepository].
     */
    @Query("SELECT tags FROM bookmarks WHERE tags != ''")
    fun getAllTagStrings(): Flow<List<String>>
}

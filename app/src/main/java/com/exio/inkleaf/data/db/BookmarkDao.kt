package com.exio.inkleaf.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class BookmarkWithComic(
    @Embedded val bookmark: BookmarkEntity,
    val comicTitle: String,
    val coverPath: String?,
    val isMissing: Boolean,
    val sourceType: BookSourceType,
)

@Dao
interface BookmarkDao {
    @Query(
        """
        SELECT b.*,
               c.title AS comicTitle,
               c.coverPath AS coverPath,
               c.isMissing AS isMissing,
               c.sourceType AS sourceType
        FROM bookmarks AS b
        INNER JOIN comics AS c ON c.id = b.comicId
        WHERE c.isDraft = 0
        ORDER BY b.addedAt DESC, b.id DESC
        """
    )
    fun observeAll(): Flow<List<BookmarkWithComic>>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE comicId = :comicId
        ORDER BY globalPageIndex ASC, addedAt ASC, id ASC
        """
    )
    fun observeForComic(comicId: Long): Flow<List<BookmarkEntity>>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE comicId = :comicId
        ORDER BY addedAt DESC, id DESC
        """
    )
    suspend fun getForComic(comicId: Long): List<BookmarkEntity>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE comicId = :comicId AND targetKey = :targetKey
        LIMIT 1
        """
    )
    suspend fun getByTargetKey(comicId: Long, targetKey: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteById(id: Long)

    @Query("DELETE FROM bookmarks WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Long>)
}

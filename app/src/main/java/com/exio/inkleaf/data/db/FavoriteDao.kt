package com.exio.inkleaf.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite_pages ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoritePageEntity>>

    @Query("SELECT * FROM favorite_pages WHERE sourceFileKey = :sourceFileKey")
    fun observeForSource(sourceFileKey: String): Flow<List<FavoritePageEntity>>

    @Query("SELECT * FROM favorite_pages WHERE id = :id")
    suspend fun getById(id: Long): FavoritePageEntity?

    @Query(
        "SELECT * FROM favorite_pages " +
            "WHERE sourceFileKey = :sourceFileKey AND pageIndex = :pageIndex LIMIT 1"
    )
    suspend fun getBySourcePage(sourceFileKey: String, pageIndex: Int): FavoritePageEntity?

    @Insert suspend fun insert(favorite: FavoritePageEntity): Long

    @Query("DELETE FROM favorite_pages WHERE id = :id") suspend fun deleteById(id: Long)
}

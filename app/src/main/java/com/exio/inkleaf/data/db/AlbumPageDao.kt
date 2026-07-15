package com.exio.inkleaf.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AlbumPageDao {
    @Query("SELECT * FROM album_pages WHERE comicId = :comicId ORDER BY position")
    suspend fun getByComicId(comicId: Long): List<AlbumPageEntity>

    @Query("SELECT * FROM album_pages WHERE id = :id")
    suspend fun getById(id: String): AlbumPageEntity?

    @Query("SELECT id FROM album_pages WHERE comicId = :comicId AND position = :position LIMIT 1")
    suspend fun getIdByPosition(comicId: Long, position: Int): String?

    @Insert
    suspend fun insertAll(pages: List<AlbumPageEntity>)

    @Query("DELETE FROM album_pages WHERE comicId = :comicId")
    suspend fun deleteByComicId(comicId: Long)

    @Query("SELECT * FROM album_pages ORDER BY comicId, position")
    suspend fun getAll(): List<AlbumPageEntity>
}

package com.exio.inkleaf.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 跨表投影：目录信息 + 该目录下的漫画数。
 * @Embedded 把 SELECT 里 library_folders 的列"摊平"进 folder 字段，
 * 额外的 comicCount 列对应同名属性。
 */
data class FolderWithCount(
    @Embedded val folder: LibraryFolderEntity,
    val comicCount: Int,
)

@Dao
interface LibraryFolderDao {
    /**
     * 子查询统计每个目录的漫画数。Room 会同时追踪两张表：
     * comics 表一变（扫描入库），这个 Flow 也会推送新列表
     */
    @Query(
        """SELECT f.*, (SELECT COUNT(*) FROM comics c WHERE c.folderId = f.id) AS comicCount
           FROM library_folders f ORDER BY f.addedAt"""
    )
    fun observeFoldersWithCount(): Flow<List<FolderWithCount>>

    @Query("SELECT * FROM library_folders ORDER BY addedAt")
    suspend fun getAll(): List<LibraryFolderEntity>

    @Query("SELECT * FROM library_folders WHERE type = :type ORDER BY addedAt")
    suspend fun getByType(type: LibraryFolderType): List<LibraryFolderEntity>

    @Query("SELECT * FROM library_folders WHERE treeUri = :treeUri")
    suspend fun getByTreeUri(treeUri: String): LibraryFolderEntity?

    @Insert
    suspend fun insert(folder: LibraryFolderEntity): Long

    @Query("DELETE FROM library_folders WHERE id = :id")
    suspend fun deleteById(id: Long)
}

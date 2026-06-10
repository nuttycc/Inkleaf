package com.exio.comicreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 数据访问接口：只写方法签名 + SQL 注解，实现由 Room 在编译期生成（KSP）。
 *
 * 两个约定：
 * - 返回 Flow 的查询是"订阅"：表里数据一变，Flow 自动发出新列表，UI 跟着刷新
 * - suspend 方法 Room 自动切到后台线程执行，调用方不需要 withContext
 */
@Dao
interface ComicDao {
    @Query("SELECT * FROM comics ORDER BY lastReadAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<ComicEntity>>

    @Query("SELECT * FROM comics WHERE id = :id")
    suspend fun getById(id: Long): ComicEntity?

    @Query("SELECT * FROM comics WHERE uri = :uri")
    suspend fun getByUri(uri: String): ComicEntity?

    /** 返回新插入行的自增 id */
    @Insert
    suspend fun insert(comic: ComicEntity): Long

    @Query("UPDATE comics SET lastReadPage = :page, lastReadAt = :time WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, time: Long)

    @Query("UPDATE comics SET pageCount = :pageCount, coverPath = :coverPath WHERE id = :id")
    suspend fun updateMetadata(id: Long, pageCount: Int, coverPath: String?)

    @Query("DELETE FROM comics WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ===== 以下为目录扫描同步专用 =====

    @Query("SELECT * FROM comics WHERE folderId = :folderId")
    suspend fun getByFolderId(folderId: Long): List<ComicEntity>

    @Insert
    suspend fun insertAll(comics: List<ComicEntity>)

    @Query("UPDATE comics SET isMissing = :missing WHERE id IN (:ids)")
    suspend fun setMissing(ids: List<Long>, missing: Boolean)

    @Query("DELETE FROM comics WHERE folderId = :folderId")
    suspend fun deleteByFolderId(folderId: Long)

    /** 只更新封面，不动 pageCount（页数留给首次打开时回填） */
    @Query("UPDATE comics SET coverPath = :coverPath WHERE id = :id")
    suspend fun updateCover(id: Long, coverPath: String)

    @Query("SELECT * FROM comics WHERE coverPath IS NULL AND isMissing = 0")
    suspend fun getComicsWithoutCover(): List<ComicEntity>
}

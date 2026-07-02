package com.exio.inkleaf.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    @Query("SELECT * FROM comics WHERE fileKey = :fileKey")
    suspend fun getByFileKey(fileKey: String): ComicEntity?

    @Query("SELECT * FROM comics WHERE fileKey IN (:fileKeys)")
    suspend fun getByFileKeys(fileKeys: List<String>): List<ComicEntity>

    /** 返回新插入行的自增 id；重复时返回 -1 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(comic: ComicEntity): Long

    @Query("UPDATE comics SET lastReadPage = :page, lastReadAt = :time WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, time: Long)

    @Query("UPDATE comics SET groupId = :groupId WHERE id = :id")
    suspend fun updateGroup(id: Long, groupId: Long?)

    @Query("UPDATE comics SET pageCount = :pageCount WHERE id = :id")
    suspend fun updatePageCount(id: Long, pageCount: Int)

    @Query("DELETE FROM comics WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ===== 以下为目录扫描同步专用 =====

    @Query("SELECT * FROM comics WHERE folderId = :folderId")
    suspend fun getByFolderId(folderId: Long): List<ComicEntity>

    /**
     * IGNORE：撞 fileKey/uri 唯一索引时静默跳过该行而不是抛异常——
     * 扫描不依赖返回值，让唯一索引成为真正的"兜底"而非崩溃点
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(comics: List<ComicEntity>): List<Long>

    @Query("UPDATE comics SET isMissing = :missing WHERE id IN (:ids)")
    suspend fun setMissing(ids: List<Long>, missing: Boolean)

    @Query("DELETE FROM comics WHERE folderId = :folderId")
    suspend fun deleteByFolderId(folderId: Long)

    /** 只更新封面，不动 pageCount（页数留给首次打开时回填） */
    @Query("UPDATE comics SET coverPath = :coverPath WHERE id = :id")
    suspend fun updateCover(id: Long, coverPath: String)

    /**
     * 自动生成的封面只能填补当前快照里的空位/失效路径；如果用户已经手动
     * 换了封面，coverPath 会改变，这次 UPDATE 就不会覆盖用户选择。
     */
    @Query(
        """UPDATE comics SET coverPath = :coverPath
           WHERE id = :id
           AND (
               (:expectedCoverPath IS NULL AND coverPath IS NULL)
               OR coverPath = :expectedCoverPath
           )"""
    )
    suspend fun updateCoverIfUnchanged(
        id: Long,
        expectedCoverPath: String?,
        coverPath: String?,
    ): Int

    @Query("SELECT * FROM comics WHERE coverPath IS NULL AND isMissing = 0")
    suspend fun getComicsWithoutCover(): List<ComicEntity>
}

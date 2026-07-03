package com.exio.inkleaf.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.exio.inkleaf.data.ChapterDiff

@Dao
abstract class ChapterDao {
    @Query("SELECT * FROM chapters WHERE comicId = :comicId ORDER BY chapterIndex")
    abstract suspend fun getByComicId(comicId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE comicId = :comicId ORDER BY chapterIndex")
    abstract fun observeByComicId(comicId: Long): kotlinx.coroutines.flow.Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE comicId = :comicId AND chapterIndex = :chapterIndex")
    abstract suspend fun getByIndex(comicId: Long, chapterIndex: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE comicId = :comicId AND fileKey = :fileKey")
    abstract suspend fun getByFileKey(comicId: Long, fileKey: String): ChapterEntity?

    @Query("SELECT COUNT(*) FROM chapters WHERE comicId = :comicId")
    abstract suspend fun countByComicId(comicId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(chapter: ChapterEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    @Query("UPDATE chapters SET pageCount = :pageCount WHERE id = :id")
    abstract suspend fun updatePageCount(id: Long, pageCount: Int)

    @Query("UPDATE chapters SET isMissing = :missing WHERE id IN (:ids)")
    abstract suspend fun setMissing(ids: List<Long>, missing: Boolean)

    @Query("DELETE FROM chapters WHERE comicId = :comicId")
    abstract suspend fun deleteByComicId(comicId: Long)

    @Update
    abstract suspend fun update(chapter: ChapterEntity)

    @Query("DELETE FROM chapters WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<Long>)

    /**
     * 把一次扫描 diff 原子地落到 chapters 表。
     *
     * 必须是 @Transaction：syncSeriesChapters 的 diff 计算和落库之间若有并发
     * 或中途崩溃，半残状态会让下次同步继续撞索引或丢失进度。整个应用过程
     * 要么全成要么全回滚。重排通过 @Update（按主键 id 更新）改 chapterIndex，
     * 不会撞索引（已去掉 (comicId, chapterIndex) 的 UNIQUE 约束）。
     */
    @Transaction
    open suspend fun applyDiff(diff: ChapterDiff) {
        diff.toUpdate.forEach { update(it) }
        if (diff.toInsert.isNotEmpty()) insertAll(diff.toInsert)
        if (diff.toRestore.isNotEmpty()) setMissing(diff.toRestore, false)
        if (diff.toMarkMissing.isNotEmpty()) setMissing(diff.toMarkMissing, true)
        if (diff.toDelete.isNotEmpty()) deleteByIds(diff.toDelete)
    }
}

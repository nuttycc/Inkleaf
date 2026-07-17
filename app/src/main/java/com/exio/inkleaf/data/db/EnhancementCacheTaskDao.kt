package com.exio.inkleaf.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EnhancementCacheTaskDao {
    @Query("SELECT * FROM enhancement_cache_tasks WHERE id = :id")
    suspend fun getById(id: String): EnhancementCacheTaskEntity?

    @Query(
        "SELECT * FROM enhancement_cache_tasks WHERE comicId = :comicId " +
                "ORDER BY updatedAt DESC LIMIT 1"
    )
    fun observeLatestForComic(comicId: Long): Flow<EnhancementCacheTaskEntity?>

    @Query(
        "SELECT * FROM enhancement_cache_tasks WHERE comicId = :comicId " +
                "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun getLatestForComic(comicId: Long): EnhancementCacheTaskEntity?

    @Query(
        "SELECT * FROM enhancement_cache_tasks WHERE comicId = :comicId " +
                "AND status IN ('queued', 'running', 'waiting_for_reader', 'paused') " +
                "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun getActiveForComic(comicId: Long): EnhancementCacheTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: EnhancementCacheTaskEntity)

    @Query(
        "UPDATE enhancement_cache_tasks SET status = 'running', updatedAt = :updatedAt, " +
                "lastError = NULL WHERE id = :id " +
                "AND status IN ('queued', 'running', 'waiting_for_reader')"
    )
    suspend fun markRunning(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET status = 'waiting_for_reader', " +
                "updatedAt = :updatedAt WHERE id = :id AND status = 'running'"
    )
    suspend fun waitForReaderIfRunning(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET nextPage = :nextPage, " +
                "completedPages = :completedPages, updatedAt = :updatedAt, " +
                "lastError = NULL WHERE id = :id AND status = 'running'"
    )
    suspend fun checkpointIfRunning(
        id: String,
        nextPage: Int,
        completedPages: Int,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET status = 'paused', updatedAt = :updatedAt " +
                "WHERE id = :id AND status IN ('queued', 'running', 'waiting_for_reader')"
    )
    suspend fun pauseIfActive(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET status = 'queued', updatedAt = :updatedAt, " +
                "lastError = NULL WHERE id = :id AND status = 'paused'"
    )
    suspend fun resumeIfPaused(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET status = 'cancelled', updatedAt = :updatedAt " +
                "WHERE id = :id AND status IN " +
                "('queued', 'running', 'waiting_for_reader', 'paused')"
    )
    suspend fun cancelIfActive(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET nextPage = endPageInclusive + 1, " +
                "completedPages = totalPages, status = 'completed', updatedAt = :updatedAt, " +
                "lastError = NULL WHERE id = :id AND status = 'running'"
    )
    suspend fun completeIfRunning(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET status = :status, updatedAt = :updatedAt, " +
                "lastError = :lastError WHERE id = :id AND status IN ('queued', 'running')"
    )
    suspend fun finishWithErrorIfActive(
        id: String,
        status: String,
        updatedAt: Long,
        lastError: String,
    ): Int

    @Query("DELETE FROM enhancement_cache_tasks WHERE comicId = :comicId")
    suspend fun deleteForComic(comicId: Long)
}

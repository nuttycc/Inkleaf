package com.exio.inkleaf.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

sealed interface EnhancementCachePageCompletion {
    data object NotApplicable : EnhancementCachePageCompletion

    data class Applied(
        val task: EnhancementCacheTaskEntity,
        val newlyCompleted: Boolean,
    ) : EnhancementCachePageCompletion
}

sealed interface EnhancementCacheTaskReplacementDecision {
    data class Replaced(
        val previousTask: EnhancementCacheTaskEntity,
    ) : EnhancementCacheTaskReplacementDecision

    data class ActiveTaskChanged(
        val activeTask: EnhancementCacheTaskEntity?,
    ) : EnhancementCacheTaskReplacementDecision
}

@Dao
abstract class EnhancementCacheTaskDao {
    @Query("SELECT * FROM enhancement_cache_tasks WHERE id = :id")
    abstract suspend fun getById(id: String): EnhancementCacheTaskEntity?

    @Query(
        "SELECT * FROM enhancement_cache_tasks WHERE comicId = :comicId " +
                "ORDER BY CASE WHEN activeSlot = 1 THEN 0 ELSE 1 END, " +
                "updatedAt DESC, createdAt DESC, id DESC LIMIT 1"
    )
    abstract fun observeLatestForComic(comicId: Long): Flow<EnhancementCacheTaskEntity?>

    @Query(
        "SELECT * FROM enhancement_cache_tasks WHERE comicId = :comicId " +
                "ORDER BY CASE WHEN activeSlot = 1 THEN 0 ELSE 1 END, " +
                "updatedAt DESC, createdAt DESC, id DESC LIMIT 1"
    )
    abstract suspend fun getLatestForComic(comicId: Long): EnhancementCacheTaskEntity?

    @Query(
        "SELECT * FROM enhancement_cache_tasks WHERE comicId = :comicId AND activeSlot = 1 " +
                "ORDER BY updatedAt DESC, createdAt DESC, id DESC LIMIT 1"
    )
    abstract suspend fun getActiveForComic(comicId: Long): EnhancementCacheTaskEntity?

    @Query("SELECT * FROM enhancement_cache_tasks WHERE activeSlot = 1 LIMIT 1")
    abstract suspend fun getAnyActive(): EnhancementCacheTaskEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(task: EnhancementCacheTaskEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCompletedPage(
        page: EnhancementCacheCompletedPageEntity,
    ): Long

    @Transaction
    open suspend fun insertIfNoActive(
        task: EnhancementCacheTaskEntity,
    ): EnhancementCacheTaskEntity? {
        val active = getAnyActive()
        if (active != null) return active
        insert(task)
        return null
    }

    /**
     * Replaces only the task the caller previously observed, so a stale confirmation cannot cancel
     * a newer task created by another request.
     */
    @Transaction
    open suspend fun replaceActive(
        expectedActiveTaskId: String,
        replacement: EnhancementCacheTaskEntity,
        updatedAt: Long,
    ): EnhancementCacheTaskReplacementDecision {
        val active = getAnyActive()
        if (active?.id != expectedActiveTaskId) {
            return EnhancementCacheTaskReplacementDecision.ActiveTaskChanged(active)
        }
        cancelIfActive(active.id, updatedAt)
        insert(replacement)
        return EnhancementCacheTaskReplacementDecision.Replaced(active)
    }

    @Query(
        "SELECT * FROM enhancement_cache_tasks WHERE activeSlot = 1 " +
                "AND (:taskId IS NULL OR id = :taskId) " +
                "AND comicId = :comicId AND modelId = :modelId " +
                "AND modelRevision = :modelRevision AND sourceRevision = :sourceRevision " +
                "AND pipelineRevision = :pipelineRevision " +
                "AND :page BETWEEN startPageInclusive AND endPageInclusive LIMIT 1"
    )
    protected abstract suspend fun getActiveMatchingPage(
        taskId: String?,
        comicId: Long,
        modelId: String,
        modelRevision: String,
        sourceRevision: String,
        pipelineRevision: String,
        page: Int,
    ): EnhancementCacheTaskEntity?

    @Query(
        "UPDATE enhancement_cache_tasks SET completedPages = :completedPages, " +
                "nextPage = :nextPage, " +
                "status = CASE WHEN :isComplete THEN 'completed' ELSE status END, " +
                "activeSlot = CASE WHEN :isComplete THEN NULL ELSE activeSlot END, " +
                "updatedAt = :updatedAt, lastError = NULL WHERE id = :id"
    )
    protected abstract suspend fun refreshProgress(
        id: String,
        completedPages: Int,
        nextPage: Int,
        isComplete: Boolean,
        updatedAt: Long,
    ): Int

    @Transaction
    open suspend fun markPageCompleted(
        taskId: String?,
        comicId: Long,
        modelId: String,
        modelRevision: String,
        sourceRevision: String,
        pipelineRevision: String,
        page: Int,
        completedAt: Long,
        resultKind: String = EnhancementCachePageResultKind.ENHANCED,
    ): EnhancementCachePageCompletion {
        val task = getActiveMatchingPage(
            taskId = taskId,
            comicId = comicId,
            modelId = modelId,
            modelRevision = modelRevision,
            sourceRevision = sourceRevision,
            pipelineRevision = pipelineRevision,
            page = page,
        ) ?: return EnhancementCachePageCompletion.NotApplicable
        val newlyCompleted = insertCompletedPage(
            EnhancementCacheCompletedPageEntity(
                taskId = task.id,
                page = page,
                completedAt = completedAt,
                resultKind = resultKind,
            )
        ) != -1L
        if (newlyCompleted) {
            val completedPages = countCompletedPages(task.id)
            val isComplete = completedPages >= task.totalPages
            val nextPage = if (isComplete) {
                task.endPageInclusive + 1
            } else if (!isPageCompleted(task.id, task.nextPage)) {
                task.nextPage
            } else {
                firstMissingPageAfterCompleted(
                    taskId = task.id,
                    startPage = task.nextPage,
                    endPageInclusive = task.endPageInclusive,
                ) ?: task.endPageInclusive + 1
            }
            refreshProgress(
                id = task.id,
                completedPages = completedPages,
                nextPage = nextPage,
                isComplete = isComplete,
                updatedAt = completedAt,
            )
        }
        return EnhancementCachePageCompletion.Applied(
            task = getById(task.id) ?: return EnhancementCachePageCompletion.NotApplicable,
            newlyCompleted = newlyCompleted,
        )
    }

    @Query(
        "SELECT page FROM enhancement_cache_completed_pages WHERE taskId = :taskId " +
                "ORDER BY page ASC"
    )
    abstract suspend fun getCompletedPages(taskId: String): List<Int>

    @Query(
        "SELECT * FROM enhancement_cache_completed_pages WHERE taskId = :taskId " +
                "ORDER BY page ASC"
    )
    abstract suspend fun getCompletedPageResults(
        taskId: String,
    ): List<EnhancementCacheCompletedPageEntity>

    @Query(
        "SELECT resultKind FROM enhancement_cache_completed_pages " +
                "WHERE taskId = :taskId AND page = :page LIMIT 1"
    )
    abstract suspend fun getCompletedPageResultKind(taskId: String, page: Int): String?

    @Query(
        "SELECT COUNT(*) FROM enhancement_cache_completed_pages WHERE taskId = :taskId"
    )
    protected abstract suspend fun countCompletedPages(taskId: String): Int

    @Query(
        "SELECT MIN(completed.page + 1) FROM enhancement_cache_completed_pages AS completed " +
                "WHERE completed.taskId = :taskId " +
                "AND completed.page BETWEEN :startPage AND (:endPageInclusive - 1) " +
                "AND NOT EXISTS (SELECT 1 FROM enhancement_cache_completed_pages AS next " +
                "WHERE next.taskId = completed.taskId AND next.page = completed.page + 1)"
    )
    protected abstract suspend fun firstMissingPageAfterCompleted(
        taskId: String,
        startPage: Int,
        endPageInclusive: Int,
    ): Int?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM enhancement_cache_completed_pages " +
                "WHERE taskId = :taskId AND page = :page)"
    )
    abstract suspend fun isPageCompleted(taskId: String, page: Int): Boolean

    @Query(
        "UPDATE enhancement_cache_tasks SET status = 'running', updatedAt = :updatedAt, " +
                "lastError = NULL WHERE id = :id AND activeSlot = 1 " +
                "AND status IN ('queued', 'running', 'waiting_for_reader')"
    )
    abstract suspend fun markRunning(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET status = 'paused', updatedAt = :updatedAt, " +
                "lastError = NULL WHERE id = :id AND activeSlot = 1 " +
                "AND status IN ('queued', 'running', 'waiting_for_reader', 'paused_low_storage')"
    )
    abstract suspend fun pauseIfActive(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET status = 'paused_low_storage', " +
                "updatedAt = :updatedAt, lastError = :lastError WHERE id = :id " +
                "AND activeSlot = 1 AND status IN ('queued', 'running', 'waiting_for_reader')"
    )
    abstract suspend fun pauseForLowStorage(
        id: String,
        updatedAt: Long,
        lastError: String,
    ): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET status = 'queued', activeSlot = 1, " +
                "updatedAt = :updatedAt, lastError = NULL WHERE id = :id AND activeSlot = 1 " +
                "AND status IN ('paused', 'paused_low_storage')"
    )
    abstract suspend fun resumeIfPaused(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET status = 'cancelled', activeSlot = NULL, " +
                "updatedAt = :updatedAt WHERE id = :id AND activeSlot = 1"
    )
    abstract suspend fun cancelIfActive(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE enhancement_cache_tasks SET status = :status, activeSlot = NULL, " +
                "updatedAt = :updatedAt, lastError = :lastError WHERE id = :id " +
                "AND status IN ('queued', 'running', 'waiting_for_reader')"
    )
    abstract suspend fun finishWithErrorIfActive(
        id: String,
        status: String,
        updatedAt: Long,
        lastError: String,
    ): Int

    @Query("DELETE FROM enhancement_cache_tasks WHERE comicId = :comicId")
    abstract suspend fun deleteForComic(comicId: Long)
}

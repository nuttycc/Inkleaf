package com.exio.inkleaf.data.enhancement.cache

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.EnhancementCacheTaskEntity
import com.exio.inkleaf.data.db.EnhancementCacheTaskStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.util.concurrent.TimeUnit

class EnhancementCacheTaskRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).enhancementCacheTaskDao()
    private val workManager = WorkManager.getInstance(appContext)

    fun observeLatest(comicId: Long): Flow<EnhancementCacheTaskEntity?> =
        dao.observeLatestForComic(comicId)

    suspend fun start(
        comicId: Long,
        modelId: String,
        modelRevision: String,
        sourceRevision: String,
        startPageInclusive: Int,
        endPageInclusive: Int,
    ): EnhancementCacheTaskEntity {
        require(startPageInclusive >= 0)
        require(endPageInclusive >= startPageInclusive)

        val now = System.currentTimeMillis()
        dao.getActiveForComic(comicId)?.let { active ->
            dao.cancelIfActive(active.id, now)
            workManager.cancelUniqueWork(workName(comicId))
            EnhancementCacheNotifications.cancel(appContext, active.id)
        }
        dao.deleteForComic(comicId)

        val task = EnhancementCacheTaskEntity(
            id = UUID.randomUUID().toString(),
            comicId = comicId,
            modelId = modelId,
            modelRevision = modelRevision,
            sourceRevision = sourceRevision,
            startPageInclusive = startPageInclusive,
            endPageInclusive = endPageInclusive,
            nextPage = startPageInclusive,
            completedPages = 0,
            totalPages = endPageInclusive - startPageInclusive + 1,
            status = EnhancementCacheTaskStatus.QUEUED,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(task)
        enqueue(task)
        return task
    }

    suspend fun pause(taskId: String) {
        val task = dao.getById(taskId) ?: return
        if (task.status !in EnhancementCacheTaskStatus.active) return
        if (dao.pauseIfActive(task.id, System.currentTimeMillis()) == 0) return
        workManager.cancelUniqueWork(workName(task.comicId))
        dao.getById(task.id)?.let { paused ->
            EnhancementCacheNotifications.showPaused(appContext, paused)
        }
    }

    suspend fun resume(taskId: String) {
        val task = dao.getById(taskId) ?: return
        if (task.status != EnhancementCacheTaskStatus.PAUSED) return
        if (dao.resumeIfPaused(task.id, System.currentTimeMillis()) == 0) return
        val resumed = dao.getById(task.id) ?: return
        EnhancementCacheNotifications.cancel(appContext, task.id)
        enqueue(resumed)
    }

    suspend fun cancel(taskId: String) {
        val task = dao.getById(taskId) ?: return
        if (dao.cancelIfActive(task.id, System.currentTimeMillis()) == 0) return
        workManager.cancelUniqueWork(workName(task.comicId))
        EnhancementCacheNotifications.cancel(appContext, task.id)
    }

    suspend fun deleteForComic(comicId: Long) {
        dao.getLatestForComic(comicId)?.let { task ->
            EnhancementCacheNotifications.cancel(appContext, task.id)
        }
        workManager.cancelUniqueWork(workName(comicId))
        dao.deleteForComic(comicId)
    }

    private fun enqueue(task: EnhancementCacheTaskEntity) {
        val request = OneTimeWorkRequestBuilder<EnhancementCacheWorker>()
            .setInputData(workDataOf(EnhancementCacheWorker.KEY_TASK_ID to task.id))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(TAG_ACTIVE_ENHANCEMENT_CACHE)
            .build()
        workManager.enqueueUniqueWork(
            workName(task.comicId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val TAG_ACTIVE_ENHANCEMENT_CACHE = "active-enhancement-cache"

        fun workName(comicId: Long): String = "active-enhancement-cache-$comicId"
    }
}

package com.exio.inkleaf.data.enhancement.cache

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.EnhancementCachePageCompletion
import com.exio.inkleaf.data.db.EnhancementCacheTaskEntity
import com.exio.inkleaf.data.db.EnhancementCacheTaskReplacementDecision
import com.exio.inkleaf.data.db.EnhancementCacheTaskStatus
import com.exio.inkleaf.data.enhancement.EnhancementPageKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface EnhancementCacheTaskStartResult {
    data class Started(val task: EnhancementCacheTaskEntity) : EnhancementCacheTaskStartResult

    data class ActiveTaskExists(
        val activeTask: EnhancementCacheTaskEntity,
    ) : EnhancementCacheTaskStartResult
}

sealed interface EnhancementCacheTaskReplacementResult {
    data class Replaced(
        val task: EnhancementCacheTaskEntity,
        val replacedTaskId: String,
    ) : EnhancementCacheTaskReplacementResult

    data class ActiveTaskChanged(
        val activeTask: EnhancementCacheTaskEntity?,
    ) : EnhancementCacheTaskReplacementResult
}

sealed interface EnhancementCachePageRecordResult {
    data object NotApplicable : EnhancementCachePageRecordResult

    data class Recorded(
        val task: EnhancementCacheTaskEntity,
        val newlyCompleted: Boolean,
    ) : EnhancementCachePageRecordResult
}

class EnhancementCacheTaskRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).enhancementCacheTaskDao()
    private val workManager = WorkManager.getInstance(appContext)
    private val comicRepository by lazy { ComicRepository(appContext) }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repeat(RECOVERY_ATTEMPTS) { attempt ->
                val recovered = runCatching { recoverActiveTask() }
                if (recovered.isSuccess) return@launch
                Log.w(TAG, "Active enhancement cache recovery failed", recovered.exceptionOrNull())
                if (attempt < RECOVERY_ATTEMPTS - 1) {
                    delay(RECOVERY_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
    }

    fun observeLatest(comicId: Long): Flow<EnhancementCacheTaskEntity?> =
        dao.observeLatestForComic(comicId)

    suspend fun start(
        comicId: Long,
        modelId: String,
        modelRevision: String,
        sourceRevision: String,
        startPageInclusive: Int,
        endPageInclusive: Int,
    ): EnhancementCacheTaskStartResult = withContext(NonCancellable) {
        taskOperationMutex.withLock {
            val task = newTask(
                comicId = comicId,
                modelId = modelId,
                modelRevision = modelRevision,
                sourceRevision = sourceRevision,
                startPageInclusive = startPageInclusive,
                endPageInclusive = endPageInclusive,
            )
            val active = dao.insertIfNoActive(task)
            if (active != null) {
                return@withLock EnhancementCacheTaskStartResult.ActiveTaskExists(active)
            }
            enqueueOrFail(task)
            EnhancementCacheTaskStartResult.Started(task)
        }
    }

    /**
     * Explicit replacement path for a UI that has confirmed replacement with the user.
     *
     * The expected id prevents a stale confirmation from replacing a newer active task.
     */
    suspend fun replace(
        expectedActiveTaskId: String,
        comicId: Long,
        modelId: String,
        modelRevision: String,
        sourceRevision: String,
        startPageInclusive: Int,
        endPageInclusive: Int,
    ): EnhancementCacheTaskReplacementResult = withContext(NonCancellable) {
        taskOperationMutex.withLock {
            val task = newTask(
                comicId = comicId,
                modelId = modelId,
                modelRevision = modelRevision,
                sourceRevision = sourceRevision,
                startPageInclusive = startPageInclusive,
                endPageInclusive = endPageInclusive,
            )
            when (
                val decision = dao.replaceActive(
                    expectedActiveTaskId = expectedActiveTaskId,
                    replacement = task,
                    updatedAt = task.createdAt,
                )
            ) {
                is EnhancementCacheTaskReplacementDecision.ActiveTaskChanged -> {
                    EnhancementCacheTaskReplacementResult.ActiveTaskChanged(decision.activeTask)
                }

                is EnhancementCacheTaskReplacementDecision.Replaced -> {
                    EnhancementCacheNotifications.cancel(appContext, decision.previousTask.id)
                    enqueueOrFail(task)
                    EnhancementCacheTaskReplacementResult.Replaced(
                        task,
                        decision.previousTask.id,
                    )
                }
            }
        }
    }

    /**
     * Records progress only after the corresponding pinned file has been persisted successfully.
     * Matching includes the comic, model revision, source revision, and requested page range.
     */
    suspend fun recordCompletedPage(
        key: EnhancementPageKey,
        page: Int,
    ): EnhancementCachePageRecordResult = withContext(NonCancellable) {
        when (
            val result = dao.markPageCompleted(
                taskId = null,
                comicId = key.comicId,
                modelId = key.modelId,
                modelRevision = key.modelRevision,
                sourceRevision = key.sourceRevision,
                page = page,
                completedAt = System.currentTimeMillis(),
            )
        ) {
            EnhancementCachePageCompletion.NotApplicable ->
                EnhancementCachePageRecordResult.NotApplicable

            is EnhancementCachePageCompletion.Applied -> {
                if (result.task.status == EnhancementCacheTaskStatus.COMPLETED) {
                    val title = comicRepository.getComic(result.task.comicId)?.title ?: "漫画"
                    EnhancementCacheNotifications.showCompleted(appContext, result.task, title)
                }
                EnhancementCachePageRecordResult.Recorded(
                    task = result.task,
                    newlyCompleted = result.newlyCompleted,
                )
            }
        }
    }

    suspend fun pause(taskId: String) = taskOperationMutex.withLock {
        val task = dao.getById(taskId) ?: return@withLock
        if (task.status !in EnhancementCacheTaskStatus.active) return@withLock
        if (dao.pauseIfActive(task.id, System.currentTimeMillis()) == 0) return@withLock
        workManager.cancelUniqueWork(WORK_NAME).await()
        dao.getById(task.id)?.let { paused ->
            EnhancementCacheNotifications.showPaused(appContext, paused)
        }
    }

    suspend fun resume(taskId: String) = withContext(NonCancellable) {
        taskOperationMutex.withLock {
            val task = dao.getById(taskId) ?: return@withLock
            if (task.status !in EnhancementCacheTaskStatus.resumable) {
                return@withLock
            }
            if (dao.resumeIfPaused(task.id, System.currentTimeMillis()) == 0) return@withLock
            val resumed = dao.getById(task.id) ?: return@withLock
            EnhancementCacheNotifications.cancel(appContext, task.id)
            enqueueOrFail(resumed)
        }
    }

    suspend fun cancel(taskId: String) = taskOperationMutex.withLock {
        val task = dao.getById(taskId) ?: return@withLock
        if (dao.cancelIfActive(task.id, System.currentTimeMillis()) == 0) return@withLock
        workManager.cancelUniqueWork(WORK_NAME).await()
        EnhancementCacheNotifications.cancel(appContext, task.id)
    }

    suspend fun deleteForComic(comicId: Long) = taskOperationMutex.withLock {
        val active = dao.getActiveForComic(comicId)
        dao.getLatestForComic(comicId)?.let { task ->
            EnhancementCacheNotifications.cancel(appContext, task.id)
        }
        if (active != null) workManager.cancelUniqueWork(WORK_NAME).await()
        dao.deleteForComic(comicId)
    }

    /** Repairs the narrow process-death window between the Room insert and WorkManager enqueue. */
    suspend fun recoverActiveTask() = taskOperationMutex.withLock {
        val active = dao.getAnyActive() ?: return@withLock
        if (active.status !in EnhancementCacheTaskStatus.schedulable) {
            return@withLock
        }
        val taskTag = taskTag(active.id)
        val alreadyScheduled = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .first()
            .any { workInfo -> taskTag in workInfo.tags && !workInfo.state.isFinished }
        if (!alreadyScheduled) enqueueOrFail(active)
    }

    private fun newTask(
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
        return EnhancementCacheTaskEntity(
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
            activeSlot = ACTIVE_SLOT,
        )
    }

    private suspend fun enqueueOrFail(task: EnhancementCacheTaskEntity) =
        withContext(NonCancellable) {
        val request = OneTimeWorkRequestBuilder<EnhancementCacheWorker>()
            .setInputData(workDataOf(EnhancementCacheWorker.KEY_TASK_ID to task.id))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(TAG_ACTIVE_ENHANCEMENT_CACHE)
            .addTag(taskTag(task.id))
            .build()
            try {
                workManager.enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                ).await()
            } catch (error: Exception) {
                dao.finishWithErrorIfActive(
                    id = task.id,
                    status = EnhancementCacheTaskStatus.FAILED,
                    updatedAt = System.currentTimeMillis(),
                    lastError = error.message ?: "无法调度缓存任务",
                )
                throw error
            }
    }

    companion object {
        const val TAG_ACTIVE_ENHANCEMENT_CACHE = "active-enhancement-cache"
        const val WORK_NAME = "active-enhancement-cache"
        private const val ACTIVE_SLOT = 1
        private const val RECOVERY_ATTEMPTS = 3
        private const val RECOVERY_RETRY_DELAY_MS = 1_000L
        private const val TAG = "EnhancementCacheTask"
        private val taskOperationMutex = Mutex()

        @Volatile
        private var instance: EnhancementCacheTaskRepository? = null

        fun getInstance(context: Context): EnhancementCacheTaskRepository =
            instance ?: synchronized(this) {
                instance ?: EnhancementCacheTaskRepository(context.applicationContext)
                    .also { instance = it }
            }

        private fun taskTag(taskId: String): String = "enhancement-cache-task-$taskId"
    }
}

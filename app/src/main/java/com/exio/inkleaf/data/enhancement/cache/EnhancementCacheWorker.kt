package com.exio.inkleaf.data.enhancement.cache

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.EnhancementCachePageCompletion
import com.exio.inkleaf.data.db.EnhancementCachePageResultKind
import com.exio.inkleaf.data.db.EnhancementCacheTaskEntity
import com.exio.inkleaf.data.db.EnhancementCacheTaskStatus
import com.exio.inkleaf.data.enhancement.EnhancedImageDiskCache
import com.exio.inkleaf.data.enhancement.EnhancementInferenceOutcome
import com.exio.inkleaf.data.enhancement.EnhancementModelCatalog
import com.exio.inkleaf.data.enhancement.EnhancementModelRepository
import com.exio.inkleaf.data.enhancement.ENHANCEMENT_PIPELINE_REVISION
import com.exio.inkleaf.data.enhancement.EnhancementPagePlan
import com.exio.inkleaf.data.enhancement.EnhancementPersistenceStatus
import com.exio.inkleaf.data.enhancement.EnhancementRequestPriority
import com.exio.inkleaf.data.enhancement.NcnnEnhancementEngine
import com.exio.inkleaf.data.enhancement.buildEnhancementPageKey
import com.exio.inkleaf.data.enhancement.loadEnhancementSourceBitmap
import com.exio.inkleaf.data.enhancement.planEnhancementPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

class EnhancementCacheWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val database = AppDatabase.getInstance(applicationContext)
        val taskDao = database.enhancementCacheTaskDao()
        var task = taskDao.getById(taskId) ?: return Result.failure()
        if (task.status !in EnhancementCacheTaskStatus.schedulable) {
            return Result.success()
        }

        val comicRepository = ComicRepository(applicationContext)
        val comic = comicRepository.getComic(task.comicId)
            ?: return fail(task, "漫画记录不存在")
        if (taskDao.markRunning(task.id, System.currentTimeMillis()) == 0) {
            return Result.success()
        }
        task = taskDao.getById(task.id) ?: return Result.success()
        try {
            setForeground(
                EnhancementCacheNotifications.foreground(applicationContext, task, comic.title)
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return fail(task, "无法启动前台缓存任务", comic.title)
        }

        return workerMutex.withLock {
            task = taskDao.getById(task.id) ?: return@withLock Result.success()
            if (task.status != EnhancementCacheTaskStatus.RUNNING) {
                return@withLock Result.success()
            }

            val model = EnhancementModelCatalog.find(task.modelId)
                ?: return@withLock fail(task, "AI 模型不存在", comic.title)
            val currentModelRevision = model.revision
            if (currentModelRevision != task.modelRevision) {
                return@withLock expire(task, "AI 模型已更新，请重新创建缓存任务", comic.title)
            }
            if (task.pipelineRevision != ENHANCEMENT_PIPELINE_REVISION) {
                return@withLock expire(
                    task,
                    "增强预处理流程已更新，请重新创建缓存任务",
                    comic.title,
                )
            }
            if (EnhancementModelRepository.getInstance(applicationContext)
                    .installedDirectory(model.id) == null
            ) {
                return@withLock fail(task, "AI 模型尚未安装", comic.title)
            }

            val volume = try {
                comicRepository.openBook(comic)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                return@withLock fail(task, error.message ?: "无法打开漫画", comic.title)
            }
            try {
                if (volume.sourceRevision != task.sourceRevision) {
                    return@withLock expire(task, "源文件已变化，请重新创建缓存任务", comic.title)
                }
                if (
                    task.startPageInclusive !in 0 until volume.totalPageCount ||
                    task.endPageInclusive !in task.startPageInclusive until volume.totalPageCount
                ) {
                    return@withLock fail(task, "缓存页码范围无效", comic.title)
                }

                val diskCache = EnhancedImageDiskCache.getInstance(applicationContext)
                val writeToken = diskCache.writeToken(
                    buildEnhancementPageKey(task.comicId, volume, task.startPageInclusive, model)
                )
                var page = task.nextPage
                var lastProgressPublishedAt = 0L
                while (page <= task.endPageInclusive) {
                    currentCoroutineContext().ensureActive()
                    val latest = taskDao.getById(task.id) ?: return@withLock Result.success()
                    if (latest.status != EnhancementCacheTaskStatus.RUNNING) {
                        return@withLock Result.success()
                    }
                    task = latest
                    page = page.coerceAtLeast(task.nextPage)
                    if (page > task.endPageInclusive) break
                    val key = buildEnhancementPageKey(task.comicId, volume, page, model)
                    val pageResultKind = when (val plan = planEnhancementPage(volume, page, model.scale)) {
                        is EnhancementPagePlan.Skip -> EnhancementCachePageResultKind.SKIPPED
                        is EnhancementPagePlan.EnhanceFast,
                        is EnhancementPagePlan.EnhanceStrips -> {
                            val ready = diskCache.containsPinned(key) ||
                                    diskCache.promoteToPinned(key, writeToken)
                            if (!ready) {
                                val cached = NcnnEnhancementEngine.cached(applicationContext, key)
                                val outcome = if (cached != null) {
                                    NcnnEnhancementEngine.pinCached(
                                        context = applicationContext,
                                        key = key,
                                        cached = cached,
                                        priority = EnhancementRequestPriority.BULK_CACHE,
                                    )
                                } else if (plan is EnhancementPagePlan.EnhanceStrips) {
                                    NcnnEnhancementEngine.enhanceStrips(
                                        context = applicationContext,
                                        key = key,
                                        volume = volume,
                                        page = page,
                                        sourceSize = plan.sourceSize,
                                        inputPixelBudget = plan.maxInputPixels,
                                        outputByteBudget = plan.maxOutputBytes,
                                        persistTransient = false,
                                        priority = EnhancementRequestPriority.BULK_CACHE,
                                        cacheInMemory = false,
                                    )
                                } else {
                                    NcnnEnhancementEngine.enhanceBulk(
                                        context = applicationContext,
                                        key = key,
                                    ) {
                                        loadEnhancementSourceBitmap(volume, page, model.scale)
                                    }
                                }
                                when (outcome) {
                                    is EnhancementInferenceOutcome.Success -> when (
                                        outcome.persistenceStatus
                                    ) {
                                        EnhancementPersistenceStatus.PINNED -> Unit
                                        EnhancementPersistenceStatus.LOW_STORAGE ->
                                            throw EnhancementCacheLowStorageException()

                                        EnhancementPersistenceStatus.INVALIDATED ->
                                            throw IOException("增强缓存目标已失效")

                                        EnhancementPersistenceStatus.WRITE_FAILED,
                                        EnhancementPersistenceStatus.NONE ->
                                            throw IOException("增强结果写入失败")
                                    }

                                    is EnhancementInferenceOutcome.Failure -> {
                                        throw IOException(outcome.message)
                                    }
                                }
                            }
                            EnhancementCachePageResultKind.ENHANCED
                        }
                    }

                    val updatedAt = System.currentTimeMillis()
                    when (
                        val completion = taskDao.markPageCompleted(
                            taskId = task.id,
                            comicId = task.comicId,
                            modelId = task.modelId,
                            modelRevision = task.modelRevision,
                            sourceRevision = task.sourceRevision,
                            pipelineRevision = task.pipelineRevision,
                            page = page,
                            completedAt = updatedAt,
                            resultKind = pageResultKind,
                        )
                    ) {
                        EnhancementCachePageCompletion.NotApplicable -> {
                            return@withLock Result.success()
                        }

                        is EnhancementCachePageCompletion.Applied -> {
                            task = completion.task
                        }
                    }
                    if (task.status == EnhancementCacheTaskStatus.COMPLETED) {
                        EnhancementCacheNotifications.showCompleted(
                            applicationContext,
                            task,
                            comic.title,
                        )
                        return@withLock Result.success()
                    }
                    page = task.nextPage
                    if (updatedAt - lastProgressPublishedAt >= PROGRESS_PUBLISH_INTERVAL_MS) {
                        publishProgress(task, comic.title)
                        lastProgressPublishedAt = updatedAt
                    }
                }

                task = taskDao.getById(task.id) ?: return@withLock Result.success()
                if (task.status == EnhancementCacheTaskStatus.COMPLETED) {
                    EnhancementCacheNotifications.showCompleted(
                        applicationContext,
                        task,
                        comic.title,
                    )
                    Result.success()
                } else {
                    fail(task, "缓存进度状态不一致", comic.title)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: EnhancementCacheLowStorageException) {
                pauseForLowStorage(task, error.message ?: "存储空间不足")
            } catch (error: Exception) {
                fail(task, error.message ?: "缓存失败", comic.title)
            } finally {
                NcnnEnhancementEngine.closeVolumeAfterEnhancement(volume)
            }
        }
    }

    private suspend fun pauseForLowStorage(
        task: EnhancementCacheTaskEntity,
        message: String,
    ): Result {
        val dao = AppDatabase.getInstance(applicationContext).enhancementCacheTaskDao()
        if (
            dao.pauseForLowStorage(
                id = task.id,
                updatedAt = System.currentTimeMillis(),
                lastError = message,
            ) == 0
        ) {
            return Result.success()
        }
        val paused = dao.getById(task.id) ?: return Result.failure()
        EnhancementCacheNotifications.showPaused(applicationContext, paused)
        return Result.success()
    }

    private suspend fun fail(
        task: EnhancementCacheTaskEntity,
        message: String,
        comicTitle: String = "漫画",
    ): Result {
        val dao = AppDatabase.getInstance(applicationContext).enhancementCacheTaskDao()
        if (
            dao.finishWithErrorIfActive(
                id = task.id,
                status = EnhancementCacheTaskStatus.FAILED,
                updatedAt = System.currentTimeMillis(),
                lastError = message,
            ) == 0
        ) {
            return Result.success()
        }
        val failed = dao.getById(task.id) ?: return Result.failure()
        EnhancementCacheNotifications.showFailed(applicationContext, failed, comicTitle, message)
        return Result.failure()
    }

    private suspend fun expire(
        task: EnhancementCacheTaskEntity,
        message: String,
        comicTitle: String,
    ): Result {
        val dao = AppDatabase.getInstance(applicationContext).enhancementCacheTaskDao()
        if (
            dao.finishWithErrorIfActive(
                id = task.id,
                status = EnhancementCacheTaskStatus.EXPIRED,
                updatedAt = System.currentTimeMillis(),
                lastError = message,
            ) == 0
        ) {
            return Result.success()
        }
        val expired = dao.getById(task.id) ?: return Result.failure()
        EnhancementCacheNotifications.showFailed(applicationContext, expired, comicTitle, message)
        return Result.failure()
    }

    private suspend fun publishProgress(
        task: EnhancementCacheTaskEntity,
        comicTitle: String,
    ) {
        try {
            setProgress(
                workDataOf(
                    KEY_COMPLETED_PAGES to task.completedPages,
                    KEY_TOTAL_PAGES to task.totalPages,
                )
            )
            setForeground(
                EnhancementCacheNotifications.foreground(
                    applicationContext,
                    task,
                    comicTitle,
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Progress delivery is best-effort; the Room checkpoint remains authoritative.
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_COMPLETED_PAGES = "completed_pages"
        const val KEY_TOTAL_PAGES = "total_pages"
        private const val PROGRESS_PUBLISH_INTERVAL_MS = 750L

        private val workerMutex = Mutex()
    }
}

private class EnhancementCacheLowStorageException :
    IOException("增强结果无法持久化，存储空间可能不足")

package com.exio.inkleaf.data.enhancement.cache

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.EnhancementCacheTaskEntity
import com.exio.inkleaf.data.db.EnhancementCacheTaskStatus
import com.exio.inkleaf.data.enhancement.EnhancedImageDiskCache
import com.exio.inkleaf.data.enhancement.EnhancementInferenceOutcome
import com.exio.inkleaf.data.enhancement.EnhancementModelCatalog
import com.exio.inkleaf.data.enhancement.EnhancementModelRepository
import com.exio.inkleaf.data.enhancement.EnhancementRequestPriority
import com.exio.inkleaf.data.enhancement.NcnnEnhancementEngine
import com.exio.inkleaf.data.enhancement.buildEnhancementPageKey
import com.exio.inkleaf.data.enhancement.loadEnhancementSourceBitmap
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
        if (task.status !in setOf(
                EnhancementCacheTaskStatus.QUEUED,
                EnhancementCacheTaskStatus.RUNNING,
                EnhancementCacheTaskStatus.WAITING_FOR_READER,
            )
        ) {
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
                var page = task.nextPage.coerceAtLeast(task.startPageInclusive)
                var completedPages = task.completedPages
                var lastProgressPublishedAt = 0L
                while (page <= task.endPageInclusive) {
                    currentCoroutineContext().ensureActive()
                    val latest = taskDao.getById(task.id) ?: return@withLock Result.success()
                    if (latest.status in setOf(
                            EnhancementCacheTaskStatus.PAUSED,
                            EnhancementCacheTaskStatus.CANCELLED,
                        )
                    ) {
                        return@withLock Result.success()
                    }
                    if (EnhancementReaderActivity.isReaderVisible) {
                        return@withLock if (
                            taskDao.waitForReaderIfRunning(
                                task.id,
                                System.currentTimeMillis(),
                            ) > 0
                        ) {
                            Result.retry()
                        } else {
                            Result.success()
                        }
                    }

                    val key = buildEnhancementPageKey(task.comicId, volume, page, model)
                    val ready = diskCache.containsPinned(key) ||
                            diskCache.promoteToPinned(key, writeToken)
                    if (!ready) {
                        val cached = NcnnEnhancementEngine.cached(applicationContext, key)
                        if (cached != null) {
                            if (!diskCache.writePinned(key, cached.bitmap, writeToken)) {
                                throw IOException("增强结果写入失败或存储空间不足")
                            }
                        } else {
                            val source = loadEnhancementSourceBitmap(volume, page, model.scale)
                            try {
                                when (
                                    val outcome = NcnnEnhancementEngine.enhance(
                                        context = applicationContext,
                                        key = key,
                                        source = source,
                                        persistTransient = false,
                                        priority = EnhancementRequestPriority.BULK_CACHE,
                                        cacheInMemory = false,
                                    )
                                ) {
                                    is EnhancementInferenceOutcome.Success -> {
                                        try {
                                            if (!diskCache.writePinned(
                                                    key,
                                                    outcome.bitmap,
                                                    writeToken
                                                )
                                            ) {
                                                throw IOException("增强结果写入失败或存储空间不足")
                                            }
                                        } finally {
                                            if (!outcome.memoryCached && !outcome.bitmap.isRecycled) {
                                                outcome.bitmap.recycle()
                                            }
                                        }
                                    }

                                    is EnhancementInferenceOutcome.Failure -> {
                                        throw IOException(outcome.message)
                                    }
                                }
                            } finally {
                                if (!source.isRecycled) source.recycle()
                            }
                        }
                    }

                    completedPages++
                    page++
                    val updatedAt = System.currentTimeMillis()
                    if (
                        taskDao.checkpointIfRunning(
                            id = task.id,
                            nextPage = page,
                            completedPages = completedPages,
                            updatedAt = updatedAt,
                        ) == 0
                    ) {
                        return@withLock Result.success()
                    }
                    task = task.copy(
                        nextPage = page,
                        completedPages = completedPages,
                        status = EnhancementCacheTaskStatus.RUNNING,
                        updatedAt = updatedAt,
                        lastError = null,
                    )
                    if (updatedAt - lastProgressPublishedAt >= PROGRESS_PUBLISH_INTERVAL_MS) {
                        publishProgress(task, comic.title)
                        lastProgressPublishedAt = updatedAt
                    }
                }

                if (taskDao.completeIfRunning(task.id, System.currentTimeMillis()) == 0) {
                    return@withLock Result.success()
                }
                task = taskDao.getById(task.id) ?: return@withLock Result.success()
                EnhancementCacheNotifications.showCompleted(applicationContext, task, comic.title)
                Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(task, error.message ?: "缓存失败", comic.title)
            } finally {
                volume.close()
            }
        }
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

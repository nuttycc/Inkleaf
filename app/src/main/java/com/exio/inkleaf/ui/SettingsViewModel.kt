package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.CacheLimit
import com.exio.inkleaf.data.CacheSettingsRepository
import com.exio.inkleaf.data.ReaderCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the non-theme settings that remain on the general settings screen. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val cacheRepo = CacheSettingsRepository(app)

    val cacheLimit: StateFlow<CacheLimit> =
        cacheRepo.limit.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CacheLimit.AUTO,
        )

    /**
     * 当前档位对应的缓存预算（字节）。AUTO 档需要查 StatFs，放 IO 线程避免主线程读盘
     * （StrictMode DiskReadViolation）。初值 0L，UI 用 formatBytes 兜底为 "0 B"，
     * 异步算出后立即回流，配置变化（旋转）时 ViewModel 复用，不重算。
     */
    val cacheBudgetBytes: StateFlow<Long> =
        cacheLimit
            .map { withContext(Dispatchers.IO) { it.bytes(getApplication()) } }
            .flowOn(Dispatchers.IO)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                0L,
            )

    /**
     * AUTO 档预算（字节）。一次计算，整个 ViewModel 生命周期复用；
     * 用于 CacheLimitSheet 内 AUTO 选项的描述文案，与 [cacheBudgetBytes] 解耦
     * （切到固定档位时 [cacheBudgetBytes] 跟着变，但 AUTO 推荐值仍需稳定展示）。
     */
    val autoCacheBudgetBytes: StateFlow<Long> =
        flowOf(CacheLimit.AUTO)
            .map { withContext(Dispatchers.IO) { it.bytes(getApplication()) } }
            .flowOn(Dispatchers.IO)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                0L,
            )

    /** Categorized unified reader-cache usage shown by the settings screen. */
    private val _cacheUsage =
        MutableStateFlow(
            ReaderCache.Usage(
                localCopiesBytes = 0L,
                onlinePagesBytes = 0L,
                manifestsBytes = 0L,
                localThumbnailsBytes = 0L,
                onlineThumbnailsBytes = 0L,
            )
        )
    internal val cacheUsage: StateFlow<ReaderCache.Usage> = _cacheUsage.asStateFlow()

    private val _isClearingOnlineCache = MutableStateFlow(false)
    val isClearingOnlineCache: StateFlow<Boolean> = _isClearingOnlineCache.asStateFlow()

    private val _cacheMessage = MutableStateFlow<String?>(null)
    val cacheMessage: StateFlow<String?> = _cacheMessage.asStateFlow()

    init {
        refreshCacheUsage()
    }

    /** 改缓存档位：写入后立即按新预算清理，再刷新占用显示。 懒生效（等下次开书才清）会让"改小了占用却没变"被当成 bug */
    fun setCacheLimit(limit: CacheLimit) {
        viewModelScope.launch {
            cacheRepo.setLimit(limit)
            ReaderCache.enforceBudget(getApplication(), keep = null)
            updateCacheUsage()
        }
    }

    fun clearOnlineCache() {
        if (_isClearingOnlineCache.value) return
        viewModelScope.launch {
            _isClearingOnlineCache.value = true
            try {
                ReaderCache.clearOnlineCache(getApplication())
                updateCacheUsage()
                _cacheMessage.value = "在线漫画缓存已清除"
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                try {
                    updateCacheUsage()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The clear failure remains actionable even if recounting also fails.
                }
                _cacheMessage.value = "在线漫画缓存清理失败，请重试"
            } finally {
                _isClearingOnlineCache.value = false
            }
        }
    }

    fun consumeCacheMessage() {
        _cacheMessage.value = null
    }

    private fun refreshCacheUsage() {
        viewModelScope.launch { updateCacheUsage() }
    }

    private suspend fun updateCacheUsage() {
        _cacheUsage.value = withContext(Dispatchers.IO) { ReaderCache.usage(getApplication()) }
    }
}

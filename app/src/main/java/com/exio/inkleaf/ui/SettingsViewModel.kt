package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.CacheLimit
import com.exio.inkleaf.data.CacheSettingsRepository
import com.exio.inkleaf.data.ReaderCache
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

    /** 缓存当前占用：设置项的"可验证反馈"，进入设置页和改档位后都会刷新 */
    private val _cacheUsageBytes = MutableStateFlow(0L)
    val cacheUsageBytes: StateFlow<Long> = _cacheUsageBytes.asStateFlow()

    init {
        refreshCacheUsage()
    }

    /** 改缓存档位：写入后立即按新预算清理，再刷新占用显示。 懒生效（等下次开书才清）会让"改小了占用却没变"被当成 bug */
    fun setCacheLimit(limit: CacheLimit) {
        viewModelScope.launch {
            cacheRepo.setLimit(limit)
            ReaderCache.enforceBudget(getApplication(), keep = null)
            refreshCacheUsage()
        }
    }

    private fun refreshCacheUsage() {
        viewModelScope.launch {
            _cacheUsageBytes.value =
                withContext(Dispatchers.IO) {
                    ReaderCache.usageBytes(getApplication())
                }
        }
    }
}

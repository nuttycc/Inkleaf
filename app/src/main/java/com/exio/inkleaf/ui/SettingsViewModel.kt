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

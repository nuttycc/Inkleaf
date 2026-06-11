package com.exio.comicreader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.comicreader.data.CacheLimit
import com.exio.comicreader.data.CacheSettingsRepository
import com.exio.comicreader.data.DarkMode
import com.exio.comicreader.data.ReaderCache
import com.exio.comicreader.data.ThemeSeed
import com.exio.comicreader.data.ThemeSettings
import com.exio.comicreader.data.ThemeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页状态。注意：主题真正的"应用"发生在 MainActivity 顶层
 * （那里也订阅同一个 DataStore Flow）——这里只负责写入；
 * 写入后全 App 变色是同一条数据链路的自然结果，无需手动通知。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val themeRepo = ThemeSettingsRepository(app)
    private val cacheRepo = CacheSettingsRepository(app)

    val theme: StateFlow<ThemeSettings> = themeRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeSettings())

    val cacheLimit: StateFlow<CacheLimit> = cacheRepo.limit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CacheLimit.AUTO)

    /** 缓存当前占用：设置项的"可验证反馈"，进入设置页和改档位后都会刷新 */
    private val _cacheUsageBytes = MutableStateFlow(0L)
    val cacheUsageBytes: StateFlow<Long> = _cacheUsageBytes.asStateFlow()

    init {
        refreshCacheUsage()
    }

    fun setSeed(seed: ThemeSeed) {
        viewModelScope.launch { themeRepo.setSeed(seed) }
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { themeRepo.setDarkMode(mode) }
    }

    fun setUseWallpaper(use: Boolean) {
        viewModelScope.launch { themeRepo.setUseWallpaper(use) }
    }

    /**
     * 改缓存档位：写入后立即按新预算清理，再刷新占用显示。
     * 懒生效（等下次开书才清）会让"改小了占用却没变"被当成 bug
     */
    fun setCacheLimit(limit: CacheLimit) {
        viewModelScope.launch {
            cacheRepo.setLimit(limit)
            ReaderCache.enforceBudget(getApplication(), keep = null)
            refreshCacheUsage()
        }
    }

    private fun refreshCacheUsage() {
        viewModelScope.launch {
            _cacheUsageBytes.value = withContext(Dispatchers.IO) {
                ReaderCache.usageBytes(getApplication())
            }
        }
    }
}

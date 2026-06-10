package com.exio.comicreader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.comicreader.data.DarkMode
import com.exio.comicreader.data.ThemeSeed
import com.exio.comicreader.data.ThemeSettings
import com.exio.comicreader.data.ThemeSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页状态。注意：主题真正的"应用"发生在 MainActivity 顶层
 * （那里也订阅同一个 DataStore Flow）——这里只负责写入；
 * 写入后全 App 变色是同一条数据链路的自然结果，无需手动通知。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val themeRepo = ThemeSettingsRepository(app)

    val theme: StateFlow<ThemeSettings> = themeRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeSettings())

    fun setSeed(seed: ThemeSeed) {
        viewModelScope.launch { themeRepo.setSeed(seed) }
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { themeRepo.setDarkMode(mode) }
    }

    fun setUseWallpaper(use: Boolean) {
        viewModelScope.launch { themeRepo.setUseWallpaper(use) }
    }
}

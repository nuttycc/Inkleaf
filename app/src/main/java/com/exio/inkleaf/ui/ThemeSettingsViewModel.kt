package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.exio.inkleaf.data.ThemeSettings
import com.exio.inkleaf.data.ThemeSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ThemeSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ThemeSettingsRepository(app)

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val saveMutex = Mutex()
    private var lastPersistedSettings: ThemeSettings? = null

    fun initialize(settings: ThemeSettings) {
        if (lastPersistedSettings == null) {
            lastPersistedSettings = settings
        }
    }

    suspend fun persistTheme(settings: ThemeSettings): Boolean = saveMutex.withLock {
        if (lastPersistedSettings == settings) return@withLock true

        _saveError.value = null
        try {
            repository.setSettings(settings)
            lastPersistedSettings = settings
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _saveError.value = "无法保存主题设置，请重试"
            false
        }
    }

    fun consumeSaveError() {
        _saveError.value = null
    }
}

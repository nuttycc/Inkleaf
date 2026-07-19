package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.ThemeSettings
import com.exio.inkleaf.data.ThemeSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ThemeSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ThemeSettingsRepository(app)

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _pendingApplication = MutableStateFlow<ThemeSettings?>(null)
    val pendingApplication: StateFlow<ThemeSettings?> = _pendingApplication.asStateFlow()

    fun applyTheme(settings: ThemeSettings) {
        if (_isSaving.value || _pendingApplication.value != null) return
        _isSaving.value = true
        _saveError.value = null
        viewModelScope.launch {
            runCatching { repository.setSettings(settings) }
                .onSuccess {
                    _pendingApplication.value = settings
                    _isSaving.value = false
                }
                .onFailure {
                    _saveError.value = "无法保存主题设置，请重试"
                    _isSaving.value = false
                }
        }
    }

    fun consumeSaveError() {
        _saveError.value = null
    }

    fun consumePendingApplication() {
        _pendingApplication.value = null
    }
}

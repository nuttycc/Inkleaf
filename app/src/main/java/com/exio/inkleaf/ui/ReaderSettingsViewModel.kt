package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.ReaderPageDirection
import com.exio.inkleaf.data.ReaderPageStatusColor
import com.exio.inkleaf.data.ReaderPageStatusPosition
import com.exio.inkleaf.data.ReaderSettings
import com.exio.inkleaf.data.ReaderSettingsRepository
import com.exio.inkleaf.data.ReaderStageBackground
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class ReaderSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ReaderSettingsRepository(app)

    val settings: StateFlow<ReaderSettings> =
        repository.settings.stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            ReaderSettings(),
        )

    fun setPageDirection(value: ReaderPageDirection) {
        viewModelScope.launch { repository.setPageDirection(value) }
    }

    fun setStageBackground(value: ReaderStageBackground) {
        viewModelScope.launch { repository.setStageBackground(value) }
    }

    fun setPageStatusPosition(value: ReaderPageStatusPosition) {
        viewModelScope.launch { repository.setPageStatusPosition(value) }
    }

    fun setPageStatusColor(value: ReaderPageStatusColor) {
        viewModelScope.launch { repository.setPageStatusColor(value) }
    }
}

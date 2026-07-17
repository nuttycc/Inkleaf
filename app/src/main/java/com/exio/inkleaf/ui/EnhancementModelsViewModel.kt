package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.enhancement.EnhancementModelCatalog
import com.exio.inkleaf.data.enhancement.EnhancementModelInstallState
import com.exio.inkleaf.data.enhancement.EnhancementModelRepository
import com.exio.inkleaf.data.enhancement.NcnnEnhancementEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Shared UI facade for model package installation state. */
class EnhancementModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = EnhancementModelRepository.getInstance(app)
    private val comicRepository = ComicRepository(app)

    val modelStates: StateFlow<Map<String, EnhancementModelInstallState>> = combine(
        EnhancementModelCatalog.models.map { model -> repository.state(model.id) }
    ) { states ->
        EnhancementModelCatalog.models.mapIndexed { index, model ->
            model.id to states[index]
        }.toMap()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EnhancementModelCatalog.models.associate { model ->
            model.id to EnhancementModelInstallState.Checking
        },
    )

    val installedCount = repository.installedCount
    val installedBytes = repository.installedBytes
    val bundledCount: Int = EnhancementModelCatalog.models.count { it.isBundled }
    val isChecking = modelStates.map { states ->
        states.values.any { it is EnhancementModelInstallState.Checking }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun install(modelId: String) {
        if (EnhancementModelCatalog.require(modelId).isBundled) return
        NcnnEnhancementEngine.enableModel(modelId)
        repository.install(modelId)
    }

    fun cancel(modelId: String) = repository.cancel(modelId)

    fun delete(modelId: String) {
        if (EnhancementModelCatalog.require(modelId).isBundled) return
        viewModelScope.launch {
            comicRepository.resetEnhancementSelections(modelId)
            try {
                NcnnEnhancementEngine.evictModel(modelId)
            } finally {
                // Once eviction starts, the repository-owned job must finish independently of UI.
                repository.delete(modelId)
            }
        }
    }

}

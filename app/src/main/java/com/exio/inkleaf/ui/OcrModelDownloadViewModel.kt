package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.ocr.OcrDownloadProgress
import com.exio.inkleaf.data.ocr.OcrModelDownloader
import com.exio.inkleaf.data.ocr.OcrModelSettingsRepository
import com.exio.inkleaf.data.ocr.OcrModelSource
import com.exio.inkleaf.data.ocr.OcrModelVariant
import com.exio.inkleaf.data.ocr.OcrSourceSelector
import com.exio.inkleaf.data.ocr.ocrModelDir
import com.exio.inkleaf.data.ocr.isOcrModelReady
import com.exio.inkleaf.data.ocr.totalBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

sealed interface OcrDownloadUiState {
    data object SelectingSource : OcrDownloadUiState
    data class ReadyToDownload(val sourceName: String, val totalBytes: Long) : OcrDownloadUiState
    data class Downloading(
        val sourceName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val currentFileName: String,
    ) : OcrDownloadUiState
    data object Completed : OcrDownloadUiState
    data class Error(val message: String, val sourceName: String?) : OcrDownloadUiState
    data object NoSourceAvailable : OcrDownloadUiState
}

data class OcrModelOption(
    val variant: OcrModelVariant,
    val installed: Boolean,
    val active: Boolean,
)

class OcrModelDownloadViewModel(app: Application) : AndroidViewModel(app) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val sourceSelector = OcrSourceSelector(client)
    private val downloader = OcrModelDownloader(client)
    private val settings = OcrModelSettingsRepository(app)
    private val filesDir = app.filesDir
    private val _selectedVariant = MutableStateFlow(OcrModelVariant.SMALL)
    val selectedVariant = _selectedVariant.asStateFlow()
    private val _state = MutableStateFlow<OcrDownloadUiState?>(null)
    val state = _state.asStateFlow()
    val options = combine(settings.activeVariant, _selectedVariant) { active, selected ->
        OcrModelVariant.entries.map { variant ->
            OcrModelOption(variant, isOcrModelReady(filesDir, variant), variant == active)
        }.also { if (selected !in OcrModelVariant.entries) _selectedVariant.value = active }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var selectedSource: OcrModelSource? = null
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch { settings.activeVariant.collect { _selectedVariant.value = it } }
    }

    fun selectVariant(variant: OcrModelVariant) {
        if (downloadJob?.isActive != true) _selectedVariant.value = variant
    }

    fun selectSource() {
        val variant = _selectedVariant.value
        _state.value = OcrDownloadUiState.SelectingSource
        viewModelScope.launch {
            val source = sourceSelector.selectBestSource(variant)
            if (source == null) {
                _state.value = OcrDownloadUiState.NoSourceAvailable
            } else {
                selectedSource = source
                _state.value = OcrDownloadUiState.ReadyToDownload(source.name, variant.totalBytes)
            }
        }
    }

    fun startDownload() {
        val variant = _selectedVariant.value
        val source = selectedSource ?: run { selectSource(); return }
        downloadJob?.cancel()
        _state.value = OcrDownloadUiState.Downloading(source.name, 0L, variant.totalBytes, "")
        downloadJob = viewModelScope.launch {
            downloader.download(source, ocrModelDir(filesDir, variant), variant).collect { progress: OcrDownloadProgress ->
                _state.update { OcrDownloadUiState.Downloading(source.name, progress.downloadedBytes, progress.totalBytes, progress.currentFileName) }
            }
            settings.setActiveVariant(variant)
            _state.value = OcrDownloadUiState.Completed
        }
        downloadJob?.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                _state.value = OcrDownloadUiState.Error(error.message ?: "下载失败", source.name)
            }
        }
    }

    fun activateSelected() {
        val variant = _selectedVariant.value
        if (isOcrModelReady(filesDir, variant)) viewModelScope.launch { settings.setActiveVariant(variant); _state.value = OcrDownloadUiState.Completed }
    }

    fun retryDownload() = startDownload()

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        // Keep partial files so a later retry can resume with HTTP Range.
    }

    fun deleteVariant(variant: OcrModelVariant) {
        if (variant == selectedVariant.value) return
        ocrModelDir(filesDir, variant).deleteRecursively()
    }

    override fun onCleared() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        super.onCleared()
    }
}

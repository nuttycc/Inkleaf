// OCR 模型下载界面的 ViewModel。
package com.exio.inkleaf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.data.ocr.OCR_MODEL_FILES
import com.exio.inkleaf.data.ocr.OCR_MODEL_TOTAL_BYTES
import com.exio.inkleaf.data.ocr.OcrDownloadProgress
import com.exio.inkleaf.data.ocr.OcrModelDownloader
import com.exio.inkleaf.data.ocr.OcrModelSource
import com.exio.inkleaf.data.ocr.OcrSourceSelector
import com.exio.inkleaf.data.ocr.ocrModelDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

sealed interface OcrDownloadUiState {
    /** 正在测速选源。 */
    data object SelectingSource : OcrDownloadUiState

    /** 测速完成，等待用户确认开始下载。 */
    data class ReadyToDownload(
        val sourceName: String,
        val sampleUrl: String,
        val totalBytes: Long,
    ) : OcrDownloadUiState

    /** 下载中。 */
    data class Downloading(
        val sourceName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val currentFileName: String,
    ) : OcrDownloadUiState

    /** 下载完成。 */
    data object Completed : OcrDownloadUiState

    /** 出错。 */
    data class Error(
        val message: String,
        val sourceName: String?,
    ) : OcrDownloadUiState

    /** 无可用源（全部超时）。 */
    data object NoSourceAvailable : OcrDownloadUiState
}

class OcrModelDownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val sourceSelector = OcrSourceSelector(client)
    private val downloader = OcrModelDownloader(client)

    private val _state = MutableStateFlow<OcrDownloadUiState>(OcrDownloadUiState.SelectingSource)
    val state = _state.asStateFlow()

    private var downloadJob: Job? = null
    private var selectedSource: OcrModelSource? = null

    init {
        selectSource()
    }

    fun selectSource() {
        _state.value = OcrDownloadUiState.SelectingSource
        viewModelScope.launch {
            val source = sourceSelector.selectBestSource()
            if (source == null) {
                _state.value = OcrDownloadUiState.NoSourceAvailable
            } else {
                selectedSource = source
                val sampleRef = OCR_MODEL_FILES.last().let { spec ->
                    source.resolveUrl(spec.repo, spec.fileName)
                }
                _state.value = OcrDownloadUiState.ReadyToDownload(
                    sourceName = source.name,
                    sampleUrl = sampleRef,
                    totalBytes = OCR_MODEL_TOTAL_BYTES,
                )
            }
        }
    }

    fun startDownload() {
        val source = selectedSource ?: return
        // 防御重入：先取消上一个未完成的下载任务
        downloadJob?.cancel()
        val modelDir = ocrModelDir(getApplication<Application>().filesDir)
        _state.value = OcrDownloadUiState.Downloading(
            sourceName = source.name,
            downloadedBytes = 0L,
            totalBytes = OCR_MODEL_TOTAL_BYTES,
            currentFileName = "",
        )
        downloadJob = viewModelScope.launch {
            downloader.download(source, modelDir)
                .collect { progress: OcrDownloadProgress ->
                    _state.update {
                        OcrDownloadUiState.Downloading(
                            sourceName = source.name,
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                            currentFileName = progress.currentFileName,
                        )
                    }
                }
            _state.value = OcrDownloadUiState.Completed
        }
        downloadJob?.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                _state.value = OcrDownloadUiState.Error(
                    message = error.message ?: "下载失败",
                    sourceName = source.name,
                )
            }
        }
    }

    fun retryDownload() {
        startDownload()
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        // 清理 .partial 文件
        val modelDir = ocrModelDir(getApplication<Application>().filesDir)
        modelDir.walkTopDown()
            .filter { it.extension == "partial" }
            .forEach { it.delete() }
    }

    override fun onCleared() {
        super.onCleared()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

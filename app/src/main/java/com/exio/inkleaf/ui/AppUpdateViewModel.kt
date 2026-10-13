package com.exio.inkleaf.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.update.AppUpdateApkDownloader
import com.exio.inkleaf.update.AppUpdateChannel
import com.exio.inkleaf.update.AppUpdateCheckResult
import com.exio.inkleaf.update.AppUpdateChecker
import com.exio.inkleaf.update.AppUpdateInstaller
import com.exio.inkleaf.update.AppUpdateSettings
import com.exio.inkleaf.update.installedVersionName
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState

    data object Checking : AppUpdateUiState

    data class UpdateAvailable(
        val version: String,
        val releaseNotes: String?,
        val channel: AppUpdateChannel,
    ) : AppUpdateUiState

    data class Downloading(val progress: Float) : AppUpdateUiState

    data object UpToDate : AppUpdateUiState

    data class Error(val message: String) : AppUpdateUiState
}

class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = AppUpdateSettings(app)
    private val application = app as InkleafApplication

    private val _uiState = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

    /** The channel followed when the user has not chosen one: a preview build tracks previews. */
    private val detectedChannel: AppUpdateChannel =
        AppUpdateChecker.detectChannel(installedVersionName(application))

    val channel: StateFlow<AppUpdateChannel> =
        settings.channel
            .map { it ?: detectedChannel }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                detectedChannel,
            )

    private var downloadedApk: File? = null
    private var pendingInstallLauncher: ActivityResultLauncher<Intent>? = null

    fun checkForUpdate() {
        if (_uiState.value is AppUpdateUiState.Checking) return
        _uiState.value = AppUpdateUiState.Checking
        viewModelScope.launch {
            val checker =
                AppUpdateChecker(getApplication(), application.onlineImageCallFactory, settings)
            when (val result = checker.check(settings.channel.first())) {
                is AppUpdateCheckResult.UpToDate -> {
                    _uiState.value = AppUpdateUiState.UpToDate
                }
                is AppUpdateCheckResult.UpdateAvailable -> {
                    _uiState.value =
                        AppUpdateUiState.UpdateAvailable(
                            version = result.entry.version,
                            releaseNotes = result.entry.releaseNotes,
                            channel = result.channel,
                        )
                }
                is AppUpdateCheckResult.Error -> {
                    _uiState.value = AppUpdateUiState.Error(result.message)
                }
            }
        }
    }

    fun downloadAndInstall(installPermissionLauncher: ActivityResultLauncher<Intent>) {
        val state = _uiState.value
        if (state !is AppUpdateUiState.UpdateAvailable) return

        pendingInstallLauncher = installPermissionLauncher
        viewModelScope.launch {
            _uiState.value = AppUpdateUiState.Downloading(0f)
            val checker =
                AppUpdateChecker(getApplication(), application.onlineImageCallFactory, settings)
            val result = checker.check(settings.channel.first())
            if (result !is AppUpdateCheckResult.UpdateAvailable) {
                _uiState.value = AppUpdateUiState.Error("更新信息已过期，请重新检查")
                return@launch
            }

            val downloader = AppUpdateApkDownloader(getApplication(), application.onlineImageCallFactory)
            try {
                val apk =
                    downloader.download(result.entry) { progress ->
                        val fraction =
                            if (progress.totalBytes != null && progress.totalBytes > 0) {
                                (progress.downloadedBytes.toFloat() / progress.totalBytes)
                                    .coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        _uiState.value = AppUpdateUiState.Downloading(fraction)
                    }
                downloadedApk = apk
                triggerInstall(apk)
            } catch (error: Exception) {
                _uiState.value = AppUpdateUiState.Error("下载失败: ${error.message ?: "未知错误"}")
            }
        }
    }

    fun skipThisVersion() {
        val state = _uiState.value
        if (state !is AppUpdateUiState.UpdateAvailable) return
        viewModelScope.launch {
            settings.setSkippedVersion(state.version)
            _uiState.value = AppUpdateUiState.Idle
        }
    }

    fun onInstallPermissionGranted() {
        val apk = downloadedApk
        if (apk != null) {
            triggerInstall(apk)
        } else {
            val launcher = pendingInstallLauncher
            if (launcher != null) {
                downloadAndInstall(launcher)
            }
        }
    }

    fun setChannel(channel: AppUpdateChannel) {
        viewModelScope.launch {
            settings.setChannel(channel)
            checkForUpdate()
        }
    }

    fun dismissState() {
        _uiState.value = AppUpdateUiState.Idle
    }

    fun consumeUpToDate() {
        if (_uiState.value is AppUpdateUiState.UpToDate) {
            _uiState.value = AppUpdateUiState.Idle
        }
    }

    suspend fun checkOnStartup(): AppUpdateCheckResult {
        val checker = AppUpdateChecker(getApplication(), application.onlineImageCallFactory, settings)
        return checker.check(settings.channel.first())
    }

    private fun triggerInstall(apk: File) {
        val context: Context = getApplication()
        val launcher = pendingInstallLauncher
        if (!AppUpdateInstaller.hasInstallPermission(context)) {
            launcher?.launch(AppUpdateInstaller.createPermissionIntent(context))
            return
        }
        context.startActivity(AppUpdateInstaller.createInstallIntent(context, apk))
        _uiState.value = AppUpdateUiState.Idle
    }
}

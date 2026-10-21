package com.exio.inkleaf.update

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The observable state of one app update attempt. */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState

    data object Checking : AppUpdateState

    data class UpdateAvailable(
        val version: String,
        val releaseNotes: String?,
    ) : AppUpdateState

    data class Downloading(val progress: Float) : AppUpdateState

    data object AwaitingInstallPermission : AppUpdateState

    data object UpToDate : AppUpdateState

    data class Error(val message: String) : AppUpdateState
}

/** The platform work behind [AppUpdate]: manifest, APK transfer and package installation. */
internal interface AppUpdatePlatform {
    suspend fun check(): AppUpdateCheckResult

    suspend fun download(
        entry: AppUpdateEntry,
        onProgress: (ApkDownloadProgress) -> Unit,
    ): File

    suspend fun setSkippedVersion(version: String)

    fun hasInstallPermission(): Boolean

    fun startInstall(apk: File)
}

/**
 * Owns the app update flow: check against the remote manifest, download the APK, ask for install
 * permission when needed, and hand the APK to the system installer.
 *
 * A download re-checks the manifest first so a cached "new version" prompt cannot install an APK
 * that has since been superseded. Permission is requested through the caller-supplied callback and
 * remembered for [onInstallPermissionGranted].
 */
internal class AppUpdate(
    private val scope: CoroutineScope,
    private val platform: AppUpdatePlatform,
) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private var downloadedApk: File? = null
    private var pendingPermissionRequest: (() -> Unit)? = null

    /** Checks the manifest unless a check is already running. */
    fun check(): Job? {
        if (_state.value is AppUpdateState.Checking) return null
        _state.value = AppUpdateState.Checking
        return scope.launch {
            when (val result = platform.check()) {
                is AppUpdateCheckResult.UpToDate -> {
                    _state.value = AppUpdateState.UpToDate
                }
                is AppUpdateCheckResult.UpdateAvailable -> {
                    _state.value =
                        AppUpdateState.UpdateAvailable(
                            version = result.entry.version,
                            releaseNotes = result.entry.releaseNotes,
                        )
                }
                is AppUpdateCheckResult.Error -> {
                    _state.value = AppUpdateState.Error(result.message)
                }
            }
        }
    }

    /**
     * Re-checks the manifest, downloads the offered APK, then installs it. When install permission
     * is missing, [requestInstallPermission] is invoked instead and the download is kept for
     * [onInstallPermissionGranted].
     */
    fun downloadAndInstall(requestInstallPermission: () -> Unit): Job? {
        val current = _state.value
        if (current !is AppUpdateState.UpdateAvailable) return null

        pendingPermissionRequest = requestInstallPermission
        return scope.launch {
            _state.value = AppUpdateState.Downloading(0f)
            val result = platform.check()
            if (result !is AppUpdateCheckResult.UpdateAvailable) {
                _state.value = AppUpdateState.Error("更新信息已过期，请重新检查")
                return@launch
            }

            try {
                val apk =
                    platform.download(result.entry) { progress ->
                        val fraction =
                            if (progress.totalBytes != null && progress.totalBytes > 0) {
                                (progress.downloadedBytes.toFloat() / progress.totalBytes)
                                    .coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        _state.value = AppUpdateState.Downloading(fraction)
                    }
                downloadedApk = apk
                triggerInstall(apk)
            } catch (error: Exception) {
                _state.value =
                    AppUpdateState.Error("下载失败: ${error.message ?: "未知错误"}")
            }
        }
    }

    fun skipThisVersion(): Job? {
        val current = _state.value
        if (current !is AppUpdateState.UpdateAvailable) return null
        return scope.launch {
            platform.setSkippedVersion(current.version)
            _state.value = AppUpdateState.Idle
        }
    }

    fun onInstallPermissionGranted() {
        val apk = downloadedApk
        if (apk != null) {
            triggerInstall(apk, requestPermission = false)
        } else {
            pendingPermissionRequest?.let { request -> downloadAndInstall(request) }
        }
    }

    fun retryInstall() {
        if (_state.value !is AppUpdateState.AwaitingInstallPermission) return
        downloadedApk?.let { triggerInstall(it) }
    }

    fun dismissState() {
        downloadedApk = null
        pendingPermissionRequest = null
        _state.value = AppUpdateState.Idle
    }

    fun consumeUpToDate() {
        if (_state.value is AppUpdateState.UpToDate) {
            _state.value = AppUpdateState.Idle
        }
    }

    /** The settings screen's entry check: reports the result without touching [state]. */
    suspend fun checkOnStartup(): AppUpdateCheckResult = platform.check()

    private fun triggerInstall(apk: File, requestPermission: Boolean = true) {
        if (!platform.hasInstallPermission()) {
            _state.value = AppUpdateState.AwaitingInstallPermission
            if (requestPermission) pendingPermissionRequest?.invoke()
            return
        }
        platform.startInstall(apk)
        _state.value = AppUpdateState.Idle
    }
}

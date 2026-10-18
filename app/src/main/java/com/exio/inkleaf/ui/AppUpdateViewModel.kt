package com.exio.inkleaf.ui

import android.app.Application
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.update.ApkDownloadProgress
import com.exio.inkleaf.update.AppUpdate
import com.exio.inkleaf.update.AppUpdateApkDownloader
import com.exio.inkleaf.update.AppUpdateCheckResult
import com.exio.inkleaf.update.AppUpdateChecker
import com.exio.inkleaf.update.AppUpdateEntry
import com.exio.inkleaf.update.AppUpdateInstaller
import com.exio.inkleaf.update.AppUpdatePlatform
import com.exio.inkleaf.update.AppUpdateSettings
import com.exio.inkleaf.update.AppUpdateState
import java.io.File
import kotlinx.coroutines.flow.StateFlow

class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as InkleafApplication

    private val platform =
        object : AppUpdatePlatform {
            private val settings = AppUpdateSettings(app)

            override suspend fun check(): AppUpdateCheckResult =
                AppUpdateChecker(app, application.onlineImageCallFactory, settings).check()

            override suspend fun download(
                entry: AppUpdateEntry,
                onProgress: (ApkDownloadProgress) -> Unit,
            ): File =
                AppUpdateApkDownloader(app, application.onlineImageCallFactory)
                    .download(entry, onProgress)

            override suspend fun setSkippedVersion(version: String) {
                settings.setSkippedVersion(version)
            }

            override fun hasInstallPermission(): Boolean =
                AppUpdateInstaller.hasInstallPermission(app)

            override fun startInstall(apk: File) {
                app.startActivity(AppUpdateInstaller.createInstallIntent(app, apk))
            }
        }

    private val update = AppUpdate(scope = viewModelScope, platform = platform)

    val uiState: StateFlow<AppUpdateState> = update.state

    fun checkForUpdate() {
        update.check()
    }

    fun downloadAndInstall(installPermissionLauncher: ActivityResultLauncher<Intent>) {
        update.downloadAndInstall {
            installPermissionLauncher.launch(AppUpdateInstaller.createPermissionIntent(application))
        }
    }

    fun skipThisVersion() {
        update.skipThisVersion()
    }

    fun onInstallPermissionGranted() {
        update.onInstallPermissionGranted()
    }

    fun dismissState() {
        update.dismissState()
    }

    fun consumeUpToDate() {
        update.consumeUpToDate()
    }

    suspend fun checkOnStartup(): AppUpdateCheckResult = update.checkOnStartup()
}

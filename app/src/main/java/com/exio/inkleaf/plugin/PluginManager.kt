package com.exio.inkleaf.plugin

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Application-facing facade for package sources and runtime lifecycle. */
class PluginManager(
    private val context: Context,
    private val store: PluginPackageStore,
    private val runtimeManager: PluginRuntimeManager,
    private val onlineContentRepository: OnlineContentRepository? = null,
    private val settingsRepository: PluginSettingsRepository? = null,
    private val downloader: PluginPackageDownloader =
        PluginPackageDownloader(OkHttpClient(), File(context.cacheDir, "plugin-downloads")),
) {
    suspend fun installFile(packageFile: File, activate: Boolean = false): PluginInstallResult =
        runtimeManager.install(packageFile, activate).also { result ->
            if (activate && result.status != PluginInstallStatus.REJECTED && result.activatable) {
                result.pluginId?.let { markAvailable(it) }
            }
        }

    suspend fun installUri(uri: Uri, activate: Boolean = false): PluginInstallResult {
        val staged = copyUriToCache(uri)
        return try {
            installFile(staged, activate)
        } finally {
            deleteStagedPackage(staged)
        }
    }

    suspend fun installUrl(
        source: PluginDownloadSource,
        activate: Boolean = false,
        onProgress: (PluginDownloadProgress) -> Unit = {},
    ): PluginInstallResult {
        val downloaded = downloader.download(source, onProgress)
        return try {
            installFile(downloaded, activate)
        } finally {
            deleteStagedPackage(downloaded)
        }
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean): InstalledPlugin? {
        val updated = runtimeManager.setEnabled(pluginId, enabled)
        if (updated != null) {
            withContext(Dispatchers.IO) {
                onlineContentRepository?.setPluginAvailability(
                    pluginId,
                    if (enabled) OnlineAvailability.AVAILABLE
                    else OnlineAvailability.PLUGIN_DISABLED,
                )
            }
        }
        return updated
    }

    suspend fun recover(pluginId: String): InstalledPlugin? =
        runtimeManager.recover(pluginId).also { result ->
            if (result != null) markAvailable(pluginId)
        }

    suspend fun uninstall(pluginId: String): Boolean {
        val removed = runtimeManager.uninstall(pluginId)
        if (removed) {
            // The uninstall contract includes user-selected source settings.
            settingsRepository?.clear(pluginId)
            withContext(Dispatchers.IO) {
                onlineContentRepository?.setPluginAvailability(
                    pluginId,
                    OnlineAvailability.PLUGIN_UNINSTALLED,
                )
            }
        }
        return removed
    }

    fun installed(): List<InstalledPlugin> = store.list()

    private suspend fun markAvailable(pluginId: String) {
        withContext(Dispatchers.IO) {
            onlineContentRepository?.setPluginAvailability(pluginId, OnlineAvailability.AVAILABLE)
        }
    }

    private suspend fun deleteStagedPackage(file: File) {
        withContext(NonCancellable + Dispatchers.IO) { file.delete() }
    }

    private suspend fun copyUriToCache(uri: Uri): File =
        withContext(Dispatchers.IO) {
            val cache = File(context.cacheDir, "plugin-imports")
            if (!cache.mkdirs() && !cache.isDirectory)
                throw IOException("Unable to create plugin import cache")
            val target = File(cache, "${System.nanoTime()}.zip")
            try {
                val resolver = context.contentResolver
                val sourceLength = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                if (sourceLength != null && sourceLength > PluginStorageLimits.MAX_PACKAGE_BYTES) {
                    throw IOException("Plugin package exceeds the size limit")
                }
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > PluginStorageLimits.MAX_PACKAGE_BYTES) {
                                throw IOException("Plugin package exceeds the size limit")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IOException("Unable to open plugin package URI")
                target
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
}

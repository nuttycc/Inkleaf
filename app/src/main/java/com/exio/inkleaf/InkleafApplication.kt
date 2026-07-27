package com.exio.inkleaf

import android.app.Application
import android.util.Log
import com.exio.inkleaf.data.AlbumExporter
import com.exio.inkleaf.data.AlbumRepository
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.ReaderCache
import com.exio.inkleaf.plugin.OnlineContentRepository
import com.exio.inkleaf.plugin.PluginBrowseRepository
import com.exio.inkleaf.plugin.PluginCatalog
import com.exio.inkleaf.plugin.PluginManager
import com.exio.inkleaf.plugin.PluginPackageStore
import com.exio.inkleaf.plugin.PluginRuntimeManager
import com.exio.inkleaf.plugin.PluginSettingsRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class InkleafApplication : Application() {
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val pluginPackageStore: PluginPackageStore by lazy {
        PluginPackageStore(File(filesDir, "plugins"))
    }
    val pluginSettingsRepository: PluginSettingsRepository by lazy {
        PluginSettingsRepository(this)
    }
    val pluginRuntimeManager: PluginRuntimeManager by lazy {
        PluginRuntimeManager(
            this,
            pluginPackageStore,
            settingsRepository = pluginSettingsRepository,
        )
    }
    val pluginCatalog: PluginCatalog by lazy {
        PluginCatalog(pluginRuntimeManager)
    }
    val pluginBrowseRepository: PluginBrowseRepository by lazy {
        PluginBrowseRepository(File(cacheDir, "plugin-browse"), pluginCatalog::browse)
    }
    val pluginManager: PluginManager by lazy {
        PluginManager(
            this,
            pluginPackageStore,
            pluginRuntimeManager,
            onlineContentRepository,
            pluginSettingsRepository,
        )
    }
    val onlineContentRepository: OnlineContentRepository by lazy {
        OnlineContentRepository(File(filesDir, "online-content/state.json"))
    }
    private lateinit var shelfWarmup: Deferred<Unit>

    override fun onCreate() {
        super.onCreate()

        // Process-owned startup work must survive the short-lived Activity used to synchronize
        // a stored night mode on cold start.
        applicationScope.launch {
            cleanupRetiredEnhancementStorage()
            ReaderCache.cleanupOnColdStart(this@InkleafApplication)
            AlbumRepository(this@InkleafApplication).cleanupOnColdStart()
            AlbumExporter.cleanupOnColdStart(this@InkleafApplication)
        }
        shelfWarmup = applicationScope.async {
            // Room open runs here on cold start. Uncaught dispatcher exceptions kill the
            // process on Android — keep warmup best-effort and let the UI load empty.
            try {
                ComicRepository(this@InkleafApplication).observeAll().first()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Shelf warmup failed", error)
            }
        }
    }

    suspend fun awaitShelfWarmup() {
        shelfWarmup.await()
    }

    /** Remove generated storage owned exclusively by the retired enhancement feature. */
    private fun cleanupRetiredEnhancementStorage() {
        listOf(
                File(filesDir, "image_enhancement_models"),
                File(filesDir, "ai_enhanced_images"),
                File(cacheDir, "ai_enhanced_images"),
            )
            .forEach { directory ->
                if (!directory.exists()) return@forEach
                runCatching { directory.deleteRecursively() }
                    .onSuccess { deleted ->
                        if (!deleted) {
                            Log.w(TAG, "Unable to remove retired storage: ${directory.name}")
                        }
                    }
                    .onFailure { error ->
                        Log.w(TAG, "Retired storage cleanup failed: ${directory.name}", error)
                    }
            }
    }

    private companion object {
        const val TAG = "InkleafApp"
    }
}

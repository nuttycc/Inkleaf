package com.exio.inkleaf

import android.app.Application
import android.os.Process
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.exio.inkleaf.data.AlbumExporter
import com.exio.inkleaf.data.AlbumRepository
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.OnlinePageCache
import com.exio.inkleaf.data.ReaderCache
import com.exio.inkleaf.plugin.OnlineContentRepository
import com.exio.inkleaf.plugin.PluginBrowseRepository
import com.exio.inkleaf.plugin.PluginCatalog
import com.exio.inkleaf.plugin.PluginManager
import com.exio.inkleaf.plugin.PluginNetworkPolicy
import com.exio.inkleaf.plugin.PluginPackageStore
import com.exio.inkleaf.plugin.PluginRuntimeManager
import com.exio.inkleaf.plugin.PluginSettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import timber.log.Timber
import kotlin.system.exitProcess

class InkleafApplication : Application(), ImageLoaderFactory {
    private val coroutineErrorHandler =
        CoroutineExceptionHandler { context, error ->
            if (error is CancellationException) return@CoroutineExceptionHandler
            Timber.e(error, "Uncaught application coroutine: %s", context)
        }
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineErrorHandler)
    private val pluginPackageStore: PluginPackageStore by lazy {
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
    internal val onlinePageCache: OnlinePageCache by lazy {
        ReaderCache.onlinePageCache(this)
    }
    internal val onlineImageCallFactory: Call.Factory by lazy {
        val client =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        PluginNetworkPolicy.createCallFactory(
            this,
            client,
            followSslRedirects = true,
        )
    }
    private lateinit var shelfWarmup: Deferred<Unit>

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).callFactory { onlineImageCallFactory }.build()

    override fun onCreate() {
        super.onCreate()
        DeveloperMode.configure(DeveloperMode.isEnabled(this))
        installUncaughtExceptionHandler()

        // Process-owned startup work must survive the short-lived Activity used to synchronize
        // a stored night mode on cold start.
        applicationScope.launch {
            cleanupRetiredEnhancementStorage()
            ReaderCache.cleanupOnColdStart(this@InkleafApplication)
            AlbumRepository(this@InkleafApplication).cleanupOnColdStart()
            AlbumExporter.cleanupOnColdStart(this@InkleafApplication)
        }
        shelfWarmup = applicationScope.async {
            ComicRepository(this@InkleafApplication).observeAll().first()
        }
    }

    suspend fun awaitShelfWarmup() {
        shelfWarmup.await()
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Timber.e(error, "Uncaught exception on thread %s", thread.name)
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
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
                            Timber.w("Unable to remove retired storage: %s", directory.name)
                        }
                    }
                    .onFailure { error ->
                        Timber.w(error, "Retired storage cleanup failed: %s", directory.name)
                    }
            }
    }

}

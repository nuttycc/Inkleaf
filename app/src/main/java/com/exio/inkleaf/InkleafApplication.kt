package com.exio.inkleaf

import android.app.Application
import com.exio.inkleaf.data.AlbumExporter
import com.exio.inkleaf.data.AlbumRepository
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.ReaderCache
import com.exio.inkleaf.data.enhancement.cache.EnhancementCacheTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class InkleafApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var shelfWarmup: Deferred<Unit>

    override fun onCreate() {
        super.onCreate()
        EnhancementCacheTaskRepository.getInstance(this)

        // Process-owned startup work must survive the short-lived Activity used to synchronize
        // a stored night mode on cold start.
        applicationScope.launch {
            ReaderCache.cleanupOnColdStart(this@InkleafApplication)
            AlbumRepository(this@InkleafApplication).cleanupOnColdStart()
            AlbumExporter.cleanupOnColdStart(this@InkleafApplication)
        }
        shelfWarmup = applicationScope.async {
            ComicRepository(this@InkleafApplication).observeAll().first()
            Unit
        }
    }

    suspend fun awaitShelfWarmup() {
        shelfWarmup.await()
    }
}

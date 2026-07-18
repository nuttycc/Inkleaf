package com.exio.inkleaf

import android.app.Application
import com.exio.inkleaf.data.enhancement.cache.EnhancementCacheTaskRepository

class InkleafApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EnhancementCacheTaskRepository.getInstance(this)
    }
}

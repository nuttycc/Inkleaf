package com.exio.inkleaf.data.enhancement.cache

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EnhancementCacheActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EnhancementCacheNotifications.EXTRA_TASK_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = EnhancementCacheTaskRepository.getInstance(context)
                when (intent.action) {
                    EnhancementCacheNotifications.ACTION_PAUSE -> repository.pause(taskId)
                    EnhancementCacheNotifications.ACTION_RESUME -> repository.resume(taskId)
                    EnhancementCacheNotifications.ACTION_CANCEL -> repository.cancel(taskId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

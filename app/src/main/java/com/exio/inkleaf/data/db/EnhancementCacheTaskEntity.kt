package com.exio.inkleaf.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "enhancement_cache_tasks",
    indices = [Index(value = ["comicId"])],
)
data class EnhancementCacheTaskEntity(
    @PrimaryKey val id: String,
    val comicId: Long,
    val modelId: String,
    val modelRevision: String,
    val sourceRevision: String,
    val startPageInclusive: Int,
    val endPageInclusive: Int,
    val nextPage: Int,
    val completedPages: Int,
    val totalPages: Int,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
)

object EnhancementCacheTaskStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val WAITING_FOR_READER = "waiting_for_reader"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"

    val active = setOf(QUEUED, RUNNING, WAITING_FOR_READER, PAUSED)
}

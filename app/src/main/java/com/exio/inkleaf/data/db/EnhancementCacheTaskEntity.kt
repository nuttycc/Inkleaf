package com.exio.inkleaf.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.exio.inkleaf.data.enhancement.ENHANCEMENT_PIPELINE_REVISION_LEGACY

@Entity(
    tableName = "enhancement_cache_tasks",
    indices = [
        Index(value = ["comicId"]),
        Index(value = ["activeSlot"], unique = true),
    ],
)
data class EnhancementCacheTaskEntity(
    @PrimaryKey val id: String,
    val comicId: Long,
    val modelId: String,
    val modelRevision: String,
    val sourceRevision: String,
    @ColumnInfo(defaultValue = "'1'")
    val pipelineRevision: String = ENHANCEMENT_PIPELINE_REVISION_LEGACY,
    val startPageInclusive: Int,
    val endPageInclusive: Int,
    val nextPage: Int,
    val completedPages: Int,
    val totalPages: Int,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val activeSlot: Int?,
    val lastError: String? = null,
)

object EnhancementCacheTaskStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val WAITING_FOR_READER = "waiting_for_reader"
    const val PAUSED = "paused"
    const val PAUSED_LOW_STORAGE = "paused_low_storage"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"

    val schedulable = setOf(QUEUED, RUNNING, WAITING_FOR_READER)
    val pausable = schedulable
    val resumable = setOf(PAUSED, PAUSED_LOW_STORAGE)
    val active = schedulable + resumable
}

package com.exio.inkleaf.data.db

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "enhancement_cache_completed_pages",
    primaryKeys = ["taskId", "page"],
    foreignKeys = [
        ForeignKey(
            entity = EnhancementCacheTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EnhancementCacheCompletedPageEntity(
    val taskId: String,
    val page: Int,
    val completedAt: Long,
)

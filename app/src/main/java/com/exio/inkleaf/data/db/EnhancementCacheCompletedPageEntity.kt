package com.exio.inkleaf.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/** Durable bulk completion kind — not a zero-byte cache placeholder. */
object EnhancementCachePageResultKind {
    const val ENHANCED = "enhanced"
    const val SKIPPED = "skipped"
}

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
    /** [EnhancementCachePageResultKind] — enhanced pin vs planned skip. */
    @ColumnInfo(defaultValue = "'enhanced'")
    val resultKind: String = EnhancementCachePageResultKind.ENHANCED,
)

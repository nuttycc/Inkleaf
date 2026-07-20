package com.exio.inkleaf.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A lightweight reader location. Page artwork remains in the rebuildable reader cache. */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = ComicEntity::class,
            parentColumns = ["id"],
            childColumns = ["comicId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["comicId"]),
        Index(value = ["comicId", "targetKey"], unique = true),
        Index(value = ["addedAt"]),
    ],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val comicId: Long,
    /** Stable uniqueness key within a comic; derived from page identity when available. */
    val targetKey: String,
    /** Source-specific stable page identity used to remap an edited book. */
    val pageIdentity: String?,
    val sourceRevision: String,
    val globalPageIndex: Int,
    val chapterIndex: Int,
    val pageIndex: Int,
    val chapterTitle: String,
    val addedAt: Long,
)

package com.exio.inkleaf.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A stable page record owned by a user-created album. */
@Entity(
    tableName = "album_pages",
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
        Index(value = ["comicId", "position"]),
    ],
)
data class AlbumPageEntity(
    @PrimaryKey val id: String,
    val comicId: Long,
    val position: Int,
    /** Path relative to Context.filesDir, so app storage can move as one unit. */
    val relativePath: String,
    val displayName: String,
    /** Lowercase filename extension without a leading dot. */
    val extension: String,
)

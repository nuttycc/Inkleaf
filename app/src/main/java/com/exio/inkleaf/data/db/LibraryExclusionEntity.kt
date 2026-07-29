package com.exio.inkleaf.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A local archive explicitly removed from the shelf and hidden from directory scans. */
@Entity(tableName = "library_exclusions")
data class LibraryExclusionEntity(
    @PrimaryKey val fileKey: String,
    val excludedAt: Long,
)

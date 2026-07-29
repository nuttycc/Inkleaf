package com.exio.inkleaf.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LibraryExclusionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exclusion: LibraryExclusionEntity)

    @Query("SELECT fileKey FROM library_exclusions WHERE fileKey IN (:fileKeys)")
    suspend fun getExcludedFileKeys(fileKeys: List<String>): List<String>

    @Query("DELETE FROM library_exclusions WHERE fileKey = :fileKey")
    suspend fun deleteByFileKey(fileKey: String)
}

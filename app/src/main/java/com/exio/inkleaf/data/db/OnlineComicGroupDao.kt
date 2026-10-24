package com.exio.inkleaf.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OnlineComicGroupDao {
    @Query("SELECT * FROM online_comic_groups")
    fun observeAll(): Flow<List<OnlineComicGroupEntity>>

    @Upsert suspend fun upsert(entity: OnlineComicGroupEntity)

    @Query("DELETE FROM online_comic_groups WHERE pluginId = :pluginId AND sourceId = :sourceId")
    suspend fun delete(pluginId: String, sourceId: String)

    @Query("DELETE FROM online_comic_groups WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: Long)
}

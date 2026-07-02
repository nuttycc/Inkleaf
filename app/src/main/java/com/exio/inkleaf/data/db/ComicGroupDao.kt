package com.exio.inkleaf.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 分组信息 + 该分组下的漫画数量。 */
data class GroupWithCount(
    @Embedded val group: ComicGroupEntity,
    val comicCount: Int,
)

@Dao
interface ComicGroupDao {
    @Query(
        """SELECT g.*, (SELECT COUNT(*) FROM comics c WHERE c.groupId = g.id) AS comicCount
           FROM comic_groups g ORDER BY g.createdAt"""
    )
    fun observeGroupsWithCount(): Flow<List<GroupWithCount>>

    @Query("SELECT * FROM comic_groups WHERE id = :id")
    suspend fun getById(id: Long): ComicGroupEntity?

    @Query("SELECT * FROM comic_groups WHERE name = :name")
    suspend fun getByName(name: String): ComicGroupEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: ComicGroupEntity): Long

    @Query("UPDATE comic_groups SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Query("UPDATE comics SET groupId = NULL WHERE groupId = :groupId")
    suspend fun clearComicsInGroup(groupId: Long)

    @Query("DELETE FROM comic_groups WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Transaction
    suspend fun deleteKeepingComics(id: Long) {
        clearComicsInGroup(id)
        deleteById(id)
    }
}

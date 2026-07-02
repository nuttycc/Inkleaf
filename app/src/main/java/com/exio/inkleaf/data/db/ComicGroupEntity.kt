package com.exio.inkleaf.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 用户在书架里创建的自定义分组；不依赖漫画所在的文件目录。 */
@Entity(
    tableName = "comic_groups",
    indices = [Index(value = ["name"], unique = true)],
)
data class ComicGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

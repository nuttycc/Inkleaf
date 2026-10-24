package com.exio.inkleaf.data.db

import androidx.room.Entity
import androidx.room.Index

/** 在线书架漫画的自定义分组关系；在线记录本身仍存放在插件状态 JSON 中。 */
@Entity(
    tableName = "online_comic_groups",
    primaryKeys = ["pluginId", "sourceId"],
    indices = [Index(value = ["groupId"])],
)
data class OnlineComicGroupEntity(
    val pluginId: String,
    val sourceId: String,
    val groupId: Long,
)

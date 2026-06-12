package com.exio.comicreader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 收藏的一页漫画。imagePath / thumbnailPath 指向应用私有 filesDir 下的用户数据，
 * 不是可丢弃缓存。
 */
@Entity(
    tableName = "favorite_pages",
    indices = [
        Index(value = ["sourceUri", "pageIndex"], unique = true),
        Index(value = ["sourceComicId"]),
    ],
)
data class FavoritePageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceComicId: Long,
    val sourceUri: String,
    val sourceTitle: String,
    val pageIndex: Int,
    val pageCount: Int,
    val imagePath: String,
    val thumbnailPath: String?,
    val addedAt: Long,
)

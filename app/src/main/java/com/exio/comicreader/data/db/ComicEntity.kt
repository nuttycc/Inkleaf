package com.exio.comicreader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书架上的一本漫画 = 数据库里的一行。
 * Room 会根据这个类自动建表（类比 ORM 的 model/schema 定义）。
 */
@Entity(
    tableName = "comics",
    // uri 加唯一索引：同一个文件不允许重复入库（业务上由先查后插保证，索引是兜底）
    indices = [Index(value = ["uri"], unique = true)],
)
data class ComicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,                 // SAF Uri.toString()，重新打开文件的"钥匙"
    val title: String,               // 文件名去掉扩展名
    val pageCount: Int = 0,          // 0 = 还没成功打开过，首次打开后回填真实页数
    val lastReadPage: Int = 0,       // 阅读进度（页索引，从 0 开始）
    val coverPath: String? = null,   // 封面文件绝对路径，null = 还没生成
    val addedAt: Long,               // 添加时间戳（毫秒）
    val lastReadAt: Long = 0,        // 最近阅读时间，书架按它倒序排
    val folderId: Long? = null,      // 来自哪个库目录；null = 手动添加（不参与扫描同步）
    val isMissing: Boolean = false,  // 扫描发现文件消失 → true；文件找回 → false
)

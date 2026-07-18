package com.exio.inkleaf.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一本漫画的章节。对于 zip/cbz 单文件漫画，不创建章节行；
 * 对于 PDF 系列目录，每个 PDF 文件对应一行，按 [chapterIndex] 排序。
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = ComicEntity::class,
            parentColumns = ["id"],
            childColumns = ["comicId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // (comicId, chapterIndex) 不加 UNIQUE：章节重排时逐行 update 会撞唯一索引
    // （中间插章 / 删中间章场景）。最终顺序由 syncSeriesChapters 的 diff 保证，
    // 不需要 DB 层强制。fileKey 仍保持 UNIQUE——它是章节的稳定身份。
    indices = [
        Index(value = ["comicId", "chapterIndex"]),
        Index(value = ["comicId"]),
        Index(value = ["fileKey"], unique = true),
    ],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val comicId: Long,
    /** 章节在书中的排序索引，从 0 开始 */
    val chapterIndex: Int,
    /** 打开该章节文件的 SAF Uri */
    val uri: String,
    /** 稳定文件身份，用于扫描同步时识别同一文件 */
    val fileKey: String,
    /** 展示标题，通常取文件名去掉扩展名 */
    val title: String,
    /** Path below the granted series root; used for deterministic ordering and duplicate titles. */
    val relativePath: String = title,
    val mimeType: String? = null,
    val size: Long? = null,
    val lastModified: Long? = null,
    /** 该章节 PDF 的总页数；0 表示尚未成功打开 */
    val pageCount: Int = 0,
    /** 文件缺失标记，与 ComicEntity.isMissing 语义一致 */
    val isMissing: Boolean = false,
)

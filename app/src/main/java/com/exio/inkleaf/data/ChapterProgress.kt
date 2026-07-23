package com.exio.inkleaf.data

/**
 * 漫画阅读进度定位：章节索引 + 章节内页索引。
 *
 * 单文件漫画（zip/cbz）的 chapterIndex 固定为 0。
 */
data class ChapterProgress(
    val chapterIndex: Int,
    val pageIndex: Int,
)

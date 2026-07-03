package com.exio.inkleaf.data

/**
 * 漫画阅读进度定位：章节索引 + 章节内页索引。
 *
 * 单文件漫画（zip/cbz）的 chapterIndex 固定为 0。
 */
data class ChapterProgress(
    val chapterIndex: Int,
    val pageIndex: Int,
) {
    fun coerceTo(chapterCount: Int, pageCountForChapter: (Int) -> Int): ChapterProgress {
        val coercedChapter = chapterIndex.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
        val pages = pageCountForChapter(coercedChapter)
        val coercedPage = pageIndex.coerceIn(0, (pages - 1).coerceAtLeast(0))
        return ChapterProgress(coercedChapter, coercedPage)
    }
}
